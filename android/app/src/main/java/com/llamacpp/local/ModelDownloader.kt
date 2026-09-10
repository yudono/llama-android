package com.llamacpp.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Unduh GGUF dari HuggingFace. HF kini wajib token (401 tanpa auth),
// jadi token opsional-guna: tanpa token coba anonim, gagal -> minta token.
// Token disimpan di SharedPreferences (diisi user di drawer).
object ModelDownloader {
    private const val KEY_TOKEN = "hf_token"
    private const val KEY_FILE = "file_"
    private const val KEY_QUANT = "quant_"
    private const val KEY_TOTAL = "total_"
    const val DEFAULT_DIR = "/sdcard/models"

    // Satu-satunya sumber token & mapping file: SQLite kv
    // (dipakai juga oleh layar Settings). Whitelist repo bawaan.
    private fun db(ctx: Context) = ChatDb(ctx).also { it.migratePrefsOnce(ctx) }

    // Whitelist: hanya model bawaan yang boleh diunduh.
    fun isAllowed(repo: String): Boolean =
        AppData.models.any { it.repo == repo }

    // Folder model: bisa diubah user (default /sdcard/models).
    // Bila tak bisa ditulis -> fallback folder privat aplikasi (selalu bisa).
    fun modelsDir(ctx: Context): File {
        val custom = db(ctx).get("models_dir", DEFAULT_DIR)
            .orEmpty().trim().ifEmpty { DEFAULT_DIR }
        val dir = File(custom)
        if (dir.exists() || runCatching { dir.mkdirs() }.getOrDefault(false)) {
            if (dir.canWrite()) return dir
        }
        Log.d("llama-dl", "dir $custom tak bisa ditulis, fallback privat")
        return File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    // Lokasi lama (privat) — dicek juga agar unduhan lama tetap kepakai.
    fun legacyDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun isUsingFallback(ctx: Context): Boolean =
        modelsDir(ctx).absolutePath == legacyDir(ctx).absolutePath &&
        db(ctx).get("models_dir", DEFAULT_DIR)
            .orEmpty().trim().ifEmpty { DEFAULT_DIR } != legacyDir(ctx).absolutePath

    // True bila path custom di shared storage tapi izin All-files belum ada.
    fun needsAllFilesAccess(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return false
        val d = modelsDir(ctx).absolutePath
        val legacy = legacyDir(ctx).absolutePath
        if (d == legacy) return false
        return !android.os.Environment.isExternalStorageManager()
    }

    fun localFile(ctx: Context, filename: String): File =
        File(modelsDir(ctx), filename)

    fun legacyFile(ctx: Context, filename: String): File =
        File(legacyDir(ctx), filename)

    // File lokal siap pakai untuk (model, quant): mapping tersimpan +
    // file ada & lengkap. null = wajib download dulu.
    fun readyFile(ctx: Context, modelName: String, quant: String): File? {
        val fn = savedFilename(ctx, modelName, quant) ?: return null
        val f = storedFile(ctx, fn) ?: return null
        if (!fn.startsWith("/")) {
            val expected = savedTotal(ctx, modelName, quant)
            if (expected > 0 && f.length() < expected) return null
        }
        return f
    }

    // File tersimpan: dukung path absolut (file manual) & nama relatif.
    fun storedFile(ctx: Context, saved: String?): File? {
        if (saved.isNullOrBlank()) return null
        val candidates = if (saved.startsWith("/")) {
            listOf(File(saved))
        } else {
            listOf(File(modelsDir(ctx), saved), File(legacyDir(ctx), saved))
        }
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    fun getToken(ctx: Context): String =
        db(ctx).get(KEY_TOKEN, "") ?: ""

    fun setToken(ctx: Context, token: String) {
        db(ctx).set(KEY_TOKEN, token.trim())
    }

    fun savedFilename(ctx: Context, modelName: String, quant: String): String? =
        db(ctx).get(KEY_FILE + modelName + "_" + quant)

    fun saveFilename(ctx: Context, modelName: String, quant: String, filename: String) {
        db(ctx).set(KEY_FILE + modelName + "_" + quant, filename)
    }

    fun savedQuant(ctx: Context, modelName: String): String =
        db(ctx).get(KEY_QUANT + modelName, AiModel.DEFAULT_QUANT) ?: AiModel.DEFAULT_QUANT

    fun saveQuant(ctx: Context, modelName: String, quant: String) {
        db(ctx).set(KEY_QUANT + modelName, quant)
    }

    fun saveTotal(ctx: Context, modelName: String, quant: String, total: Long) {
        db(ctx).set(KEY_TOTAL + modelName + "_" + quant, total.toString())
    }

    fun savedTotal(ctx: Context, modelName: String, quant: String): Long =
        db(ctx).get(KEY_TOTAL + modelName + "_" + quant)?.toLongOrNull() ?: 0L

    private fun norm(s: String) = s.lowercase().replace('-', '_')

    // Cari nama file GGUF. Urutan: API resmi (otoritatif) -> konstruksi
    // langsung dari konvensi nama (Qwen/HF lowercase, unsloth/bartowski
    // case-asli, quant underscore) -> HEAD verify -> FALLBACK quant lain.
    // Tak ada drama "quant tak tersedia": bila quant pilihan tak ada,
    // otomatis pakai quant lain yang filenya ada + user diberi tahu.
    data class ResolveResult(
        val file: String?, val needToken: Boolean, val reached: Boolean,
        val quant: String, val fellBack: Boolean = false
    )

    private fun constructFilename(repo: String, quant: String): String {
        val base = repo.substringAfter('/')
            .removeSuffix("-GGUF").removeSuffix("-gguf")
        val lowerOrg = repo.substringBefore('/').lowercase() in setOf("qwen", "huggingfacetb")
        val b = if (lowerOrg) base.lowercase() else base
        val q = (if (lowerOrg) quant.lowercase() else quant.uppercase()).replace('-', '_')
        return "$b-$q.gguf"
    }

    private fun headCode(repo: String, filename: String, token: String): Int? {
        return try {
            val url = URL("https://huggingface.co/$repo/resolve/main/$filename")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod = "HEAD"
                connectTimeout = 15000; readTimeout = 15000
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
                instanceFollowRedirects = true
                responseCode
            }
        } catch (e: Exception) {
            Log.d("llama-dl", "HEAD $filename error: ${e.message}")
            null
        }
    }

    fun resolve(ctx: Context, repo: String, quant: String): ResolveResult {
        if (!isAllowed(repo)) return ResolveResult(null, false, false, quant)
        val token = getToken(ctx)
        if (token.isBlank()) return ResolveResult(null, true, false, quant)
        val q = norm(quant)
        var reached = false
        // 1. API resmi
        try {
            val url = URL("https://huggingface.co/api/models/$repo")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                val code = responseCode
                reached = true
                Log.d("llama-dl", "API $repo -> $code")
                if (code == 401) return ResolveResult(null, true, true, quant)
                if (code == 200) {
                    val arr = JSONObject(inputStream.bufferedReader().readText())
                        .optJSONArray("siblings")
                    if (arr != null) {
                        val cands = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i).optString("rfilename")
                            if (f.endsWith(".gguf") && norm(f).contains(q) &&
                                !norm(f).contains("mmproj") && !norm(f).contains("imatrix")
                            ) cands += f
                        }
                        cands.sortBy { if ("0000" in it) 1 else 0 }
                        if (cands.isNotEmpty()) {
                            Log.d("llama-dl", "API match: ${cands.first()}")
                            return ResolveResult(cands.first(), false, true, quant)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("llama-dl", "API error: ${e.message}")
        }
        // 2. Konstruksi langsung dari konvensi + HEAD verify
        val constructed = constructFilename(repo, quant)
        Log.d("llama-dl", "coba constructed: $constructed")
        when (headCode(repo, constructed, token)) {
            200 -> return ResolveResult(constructed, false, true, quant)
            401 -> return ResolveResult(null, true, true, quant)
            null -> { /* jaringan gagal total */ }
            else -> reached = true
        }
        // 3. Fallback: coba quant lain satu per satu (tanpa drama).
        for (q2 in AiModel.QUANTS) {
            if (norm(q2) == q) continue
            val f2 = constructFilename(repo, q2)
            when (headCode(repo, f2, token)) {
                200 -> {
                    Log.d("llama-dl", "fallback: $quant tak ada, pakai $q2 ($f2)")
                    return ResolveResult(f2, false, true, q2, fellBack = true)
                }
                401 -> return ResolveResult(null, true, true, quant)
                null -> return ResolveResult(null, false, reached, quant)
                else -> reached = true
            }
        }
        return ResolveResult(null, false, reached, quant)
    }

    fun downloadUrl(repo: String, filename: String) =
        "https://huggingface.co/$repo/resolve/main/$filename"

    // Antrekan DownloadManager sistem (tahan banting: retry + lanjut otomatis).
    // Tujuan = folder model aktif (shared butuh MANAGE_EXTERNAL_STORAGE).
    fun enqueue(ctx: Context, url: String, filename: String, token: String): Long {
        val dest = File(modelsDir(ctx), filename)
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading model dari HuggingFace")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            if (dest.absolutePath.startsWith(legacyDir(ctx).absolutePath)) {
                setDestinationInExternalFilesDir(ctx, "models", filename)
            } else {
                dest.parentFile?.mkdirs()
                setDestinationUri(Uri.fromFile(dest))
            }
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            if (token.isNotBlank()) addRequestHeader("Authorization", "Bearer ${token.trim()}")
        }
        return (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }

    // Scan folder: file .gguf APAPUN yang cocok nama model langsung
    // didaftarkan ke list (termasuk file dari sumber lain). Dipanggil
    // tiap start/resume agar list selalu sinkron dengan isi folder.
    fun scanDisk(ctx: Context) {
        val seen = mutableSetOf<String>()
        val dirs = listOf(modelsDir(ctx), legacyDir(ctx))
        for (dir in dirs) {
            val files = runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", true) }
            }.getOrNull() ?: continue
            for (f in files) {
                if (f.length() <= 0 || !seen.add(f.name.lowercase())) continue
                matchModel(f.name)?.let { (idx, quant) ->
                    val m = AppData.models[idx]
                    saveFilename(ctx, m.name, quant, f.name)
                    if (m.quant != quant) {
                        m.quant = quant
                        saveQuant(ctx, m.name, quant)
                    }
                    Log.d("llama-dl", "scan: ${f.name} -> ${m.shortName} $quant")
                }
            }
        }
    }

    // Cocokkan nama file ke (index model, quant): basis nama repo + quant.
    private fun matchModel(filename: String): Pair<Int, String>? {
        val nf = norm(filename)
        AppData.models.forEachIndexed { idx, m ->
            val base = norm(m.repo.substringAfter('/')
                .removeSuffix("-GGUF").removeSuffix("-gguf"))
            if (base.isNotEmpty() && nf.contains(base)) {
                val q = AiModel.QUANTS.firstOrNull { nf.contains(norm(it)) }
                    ?: m.quant
                return idx to q
            }
        }
        return null
    }

    data class Progress(val status: Int, val done: Long, val total: Long)

    fun query(ctx: Context, id: Long): Progress? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return Progress(st, done, total)
        }
    }

    fun remove(ctx: Context, id: Long) {
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
    }
}
