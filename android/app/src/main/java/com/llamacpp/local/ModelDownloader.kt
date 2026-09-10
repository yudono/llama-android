package com.llamacpp.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Unduh GGUF dari HuggingFace. HF kini wajib token (401 tanpa auth),
// jadi token opsional-guna: tanpa token coba anonim, gagal -> minta token.
// Token disimpan di SharedPreferences (diisi user di drawer).
object ModelDownloader {
    private const val PREFS = "llama_prefs"
    private const val KEY_TOKEN = "hf_token"
    private const val KEY_FILE = "file_"

    fun modelsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun localFile(ctx: Context, filename: String): File =
        File(modelsDir(ctx), filename)

    fun getToken(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""

    fun setToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token.trim()).apply()
    }

    fun savedFilename(ctx: Context, modelName: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILE + modelName, null)

    fun saveFilename(ctx: Context, modelName: String, filename: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_FILE + modelName, filename).apply()
    }

    private fun authHeader(token: String): String? =
        token.ifBlank { null }?.let { "Bearer $it" }

    private fun norm(s: String) = s.lowercase().replace('-', '_')

    // Cari nama file GGUF via HF API; fallback ke pola nama umum.
    // Kembalikan null bila butuh token (401) atau tak ketemu.
    // Hasil: Pair(filename, needToken).
    fun resolve(ctx: Context, repo: String, quant: String): Pair<String?, Boolean> {
        val token = getToken(ctx)
        val q = norm(quant)
        // 1. API resmi
        try {
            val url = URL("https://huggingface.co/api/models/$repo")
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 15000; readTimeout = 15000
                authHeader(token)?.let { setRequestProperty("Authorization", it) }
                if (responseCode == 401) return null to true
                if (responseCode == 200) {
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
                        // Utamakan single-file (bukan split -0000x-of-0000y)
                        cands.sortBy { if ("0000" in it) 1 else 0 }
                        if (cands.isNotEmpty()) return cands.first() to false
                    }
                }
            }
        } catch (_: Exception) { /* lanjut fallback */ }
        // 2. Pola nama umum (coba HEAD hemat kuota)
        val base = repo.substringAfter('/').lowercase()
        val variants = listOf(
            "$base-$q.gguf", "$base-${q.replace('_', '-')}.gguf",
            "${base.replace('-', '_')}-$q.gguf"
        )
        for (v in variants) {
            try {
                val url = URL("https://huggingface.co/$repo/resolve/main/$v")
                (url.openConnection() as HttpURLConnection).run {
                    requestMethod = "HEAD"
                    connectTimeout = 15000; readTimeout = 15000
                    authHeader(token)?.let { setRequestProperty("Authorization", it) }
                    instanceFollowRedirects = true
                    if (responseCode == 401) return null to true
                    if (responseCode == 200) return v to false
                }
            } catch (_: Exception) { /* coba berikut */ }
        }
        return null to false
    }

    fun downloadUrl(repo: String, filename: String) =
        "https://huggingface.co/$repo/resolve/main/$filename"

    // Antrekan DownloadManager sistem (tahan banting: retry + lanjut otomatis).
    fun enqueue(ctx: Context, url: String, filename: String, token: String): Long {
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading model dari HuggingFace")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationInExternalFilesDir(ctx, "models", filename)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            if (token.isNotBlank()) addRequestHeader("Authorization", "Bearer ${token.trim()}")
        }
        return (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
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
