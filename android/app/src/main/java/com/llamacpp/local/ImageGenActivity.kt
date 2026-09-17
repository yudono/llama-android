package com.llamacpp.local

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.llamacpp.local.databinding.ActivityImageGenBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Text-to-image on-device (stable-diffusion.cpp, model SDXL-Turbo GGUF).
// Alur: pilih model -> Get (download HF, bisa Cancel) -> isi prompt ->
// Generate (progress per-step, bisa Cancel) -> PNG tampil + bisa Share.
class ImageGenActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageGenBinding
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private var sel = 0
    private var generating = false

    // Cache context native (load sekali per file model, berat: mmap ±2.6GB).
    private var sdHandle = 0L
    private var handleFor = ""

    private var lastOut: File? = null
    // Catatan hasil/error permanen (pengganti toast yg hilang).
    private var genNote: String? = null
    private var genNoteError = false
    // Pembatalan salinan file lokal per model (pick).
    private val copyCancels = mutableMapOf<Int, AtomicBoolean>()
    private var pickFor = -1

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickFor >= 0) startCopy(pickFor, uri)
        pickFor = -1
    }

    companion object {
        // Kunci mapping file di SQLite kv (model image tanpa varian quant).
        const val IMG_Q = "IMG"
        // 256 utk draft super-cepat/hemat RAM; native clamp min 256.
        val SIZES = listOf(256 to 256, 512 to 512, 768 to 512, 512 to 768, 768 to 768, 1024 to 1024)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        b = ActivityImageGenBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }
        b.tvSdVer.text = runCatching { "sd.cpp ${StableDiffusionBridge.version()}" }.getOrDefault("")

        val models = AppData.imageModels
        b.spImageModel.adapter = ArrayAdapter(
            this, R.layout.item_quant,
            models.map { "${it.shortName} · ${it.fileSize}" }
        ).also { it.setDropDownViewResource(R.layout.item_quant_dropdown) }
        b.spImageModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                sel = i
                refreshDl()
                refreshGen()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        b.spSize.adapter = ArrayAdapter(
            this, R.layout.item_quant, SIZES.map { "${it.first} × ${it.second}" }
        ).also { it.setDropDownViewResource(R.layout.item_quant_dropdown) }

        b.spSampler.adapter = ArrayAdapter(
            this, R.layout.item_quant, StableDiffusionBridge.SAMPLERS
        ).also { it.setDropDownViewResource(R.layout.item_quant_dropdown) }
        b.spSampler.setSelection(0, false) // euler_a (setara contoh CLI)

        b.btnDl.setOnClickListener {
            val m = models[sel]
            if (m.downloading) cancelTransfer() else startDownload()
        }
        b.btnPickFile.setOnClickListener { launchPicker() }
        b.btnDelModel.setOnClickListener { confirmDelete() }
        b.btnGenerate.setOnClickListener { startGenerate() }
        b.btnCancelGen.setOnClickListener { cancelGenerate() }
        b.btnShare.setOnClickListener { shareLast() }

        refreshFlags()
    }

    override fun onResume() {
        super.onResume()
        refreshFlags()
    }

    private fun model() = AppData.imageModels[sel]

    private fun refreshFlags() {
        AppData.imageModels.forEach { m ->
            val f = ModelDownloader.storedFile(this, m.filename)
                ?: ModelDownloader.readyFile(this, m.name, IMG_Q)
            m.downloaded = f != null
            if (!m.downloaded) m.downloading = false
        }
        refreshDl()
        refreshGen()
    }

    // ---------- Download model (pola sama dengan ModelsActivity) ----------

    private fun selectedFile(): File? {
        val m = model()
        return ModelDownloader.storedFile(this, m.filename)
            ?: ModelDownloader.readyFile(this, m.name, IMG_Q)
    }

    private fun refreshDl() {
        val m = model()
        when {
            m.downloading -> {
                b.tvDlStatus.text = if (m.downloadId > 0) "Downloading ${m.progressLabel()}"
                                    else "Copying ${m.progressLabel()}"
                b.tvDlStatus.setTextColor(ContextCompat.getColor(this, R.color.yellow))
                b.btnDl.text = "Cancel"
                b.btnDl.setTextColor(ContextCompat.getColor(this, R.color.red))
                b.btnPickFile.visibility = View.GONE
                b.btnDelModel.visibility = View.GONE
                b.dlProgress.visibility = View.VISIBLE
                b.dlProgress.progress = m.progress.toInt()
            }
            m.downloaded -> {
                b.tvDlStatus.text = "Ready to use · ${m.fileSize}"
                b.tvDlStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
                b.btnDl.visibility = View.GONE
                b.btnPickFile.visibility = View.GONE
                b.btnDelModel.visibility = View.VISIBLE
                b.dlProgress.visibility = View.GONE
            }
            else -> {
                b.tvDlStatus.text = "Not downloaded · ${m.fileSize}"
                b.tvDlStatus.setTextColor(ContextCompat.getColor(this, R.color.text_dim))
                b.btnDl.visibility = View.VISIBLE
                b.btnDl.text = "Get"
                b.btnDl.setTextColor(ContextCompat.getColor(this, R.color.accent))
                b.btnPickFile.visibility = View.VISIBLE
                b.btnDelModel.visibility = View.GONE
                b.dlProgress.visibility = View.GONE
            }
        }
    }

    private fun startDownload() {
        val m = model()
        if (ModelDownloader.getToken(this).isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Butuh HF token")
                .setMessage("Download model dari HuggingFace butuh token gratis.\n\nIsi dulu di Settings, lalu tap Get lagi.")
                .setPositiveButton("Buka Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        if (ModelDownloader.needsAllFilesAccess(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Izin akses file")
                .setMessage("Folder model (${ModelDownloader.modelsDir(this).absolutePath}) butuh izin akses file. Beri izin dulu, lalu tap Get lagi.")
                .setPositiveButton("Buka Pengaturan") { _, _ ->
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    } catch (_: Exception) {
                        Toast.makeText(this, "Buka manual: Settings HP > Apps > izin file", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        if (m.downloading || selectedFile() != null) {
            if (selectedFile() != null) {
                m.downloaded = true
                refreshDl()
                refreshGen()
            }
            return
        }
        m.downloading = true
        m.progress = 0f
        refreshDl()
        Thread {
            try {
                ModelDownloader.saveFilename(this, m.name, IMG_Q, m.filename)
                val id = ModelDownloader.enqueue(
                    this, ModelDownloader.downloadUrl(m.repo, m.filename),
                    m.filename, ModelDownloader.getToken(this)
                )
                m.downloadId = id
                pollDownload(id)
            } catch (e: Exception) {
                handler.post {
                    m.downloading = false
                    refreshDl()
                    Toast.makeText(this, "Download gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun pollDownload(id: Long) {
        val m = model()
        val tick = object : Runnable {
            override fun run() {
                // User pindah model saat download jalan: tetap update objek asal.
                val p = ModelDownloader.query(this@ImageGenActivity, id)
                if (p == null || p.status == android.app.DownloadManager.STATUS_FAILED) {
                    m.downloading = false
                    handler.post {
                        refreshDl()
                        Toast.makeText(this@ImageGenActivity, "Download gagal", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                if (p.total > 0) ModelDownloader.saveTotal(this@ImageGenActivity, m.name, IMG_Q, p.total)
                if (p.status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100f
                    handler.post {
                        refreshDl()
                        refreshGen()
                        Toast.makeText(this@ImageGenActivity, "${m.shortName} downloaded", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                if (p.total > 0) m.progress = (100f * p.done / p.total).coerceIn(0f, 100f)
                handler.post { refreshDl() }
                handler.postDelayed(this, 800)
            }
        }
        handler.post(tick)
    }

    private fun cancelTransfer() {
        if (model().downloadId > 0) cancelDownload() else copyCancels[sel]?.set(true)
    }

    private fun cancelDownload() {
        val m = model()
        if (m.downloadId > 0) {
            ModelDownloader.remove(this, m.downloadId)
        }
        m.downloading = false
        m.progress = 0f
        m.downloadId = -1L
        refreshDl()
        Toast.makeText(this, "Download dibatalkan", Toast.LENGTH_SHORT).show()
    }

    // Pilih file .gguf sendiri (mis. hasil adb push ke Download) sbg alternatif Get.
    private fun launchPicker() {
        val m = model()
        if (m.downloading || m.downloaded) return
        pickFor = sel
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            pickFor = -1
            Toast.makeText(this, "Tak ada file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCopy(real: Int, uri: Uri) {
        val m = AppData.imageModels[real]
        val cr = contentResolver
        var rawName: String? = null
        var size = -1L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return
                rawName = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                size = if (si >= 0) c.getLong(si) else -1L
            } ?: return
        } catch (_: Exception) {
            Toast.makeText(this, "Tak bisa membaca file", Toast.LENGTH_SHORT).show()
            return
        }
        val picked = rawName ?: return
        if (!picked.endsWith(".gguf", true)) {
            Toast.makeText(this, "Pilih file .gguf", Toast.LENGTH_SHORT).show()
            return
        }
        val safe = picked.substringAfterLast('/').substringAfterLast('\\')
        val dest = File(ModelDownloader.modelsDir(this), safe)
        if (dest.exists()) dest.delete()
        m.downloading = true
        m.downloadId = -1L
        m.progress = 0f
        val cancel = AtomicBoolean(false)
        copyCancels[real] = cancel
        refreshDl()
        Thread {
            var done = 0L
            try {
                cr.openInputStream(uri)?.use { inp ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancel.get()) throw java.util.concurrent.CancellationException()
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (size > 0) {
                                m.progress = (100f * done / size).coerceIn(0f, 100f)
                                handler.post { refreshDl() }
                            }
                        }
                    }
                } ?: throw IllegalStateException("tak bisa buka file")
                if (done <= 0) throw IllegalStateException("file kosong")
                ModelDownloader.saveFilename(this, m.name, IMG_Q, safe)
                ModelDownloader.saveTotal(this, m.name, IMG_Q, done)
                handler.post {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100f
                    refreshDl()
                    refreshGen()
                    Toast.makeText(this, "${m.shortName} terpasang", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.util.concurrent.CancellationException) {
                dest.delete()
                handler.post {
                    m.downloading = false
                    m.progress = 0f
                    refreshDl()
                    Toast.makeText(this, "Penyalinan dibatalkan", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                dest.delete()
                handler.post {
                    m.downloading = false
                    m.progress = 0f
                    refreshDl()
                    Toast.makeText(this, "Gagal menyalin: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                copyCancels.remove(real)
            }
        }.start()
    }

    private fun confirmDelete() {
        val m = model()
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus model?")
            .setMessage("${m.shortName} dihapus permanen dari penyimpanan.")
            .setPositiveButton("Hapus") { _, _ -> deleteModel() }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteModel() {
        val m = model()
        val f = ModelDownloader.storedFile(this, m.filename)
        val ok = f?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        if (handleFor.isNotEmpty() && (f == null || handleFor == f.absolutePath)) {
            runCatching { StableDiffusionBridge.freeContext(sdHandle) }
            sdHandle = 0L
            handleFor = ""
        }
        ModelDownloader.saveFilename(this, m.name, IMG_Q, "")
        ModelDownloader.saveTotal(this, m.name, IMG_Q, 0L)
        m.downloaded = false
        m.downloading = false
        m.progress = 0f
        refreshDl()
        refreshGen()
        Toast.makeText(
            this,
            if (ok || f == null) "${m.shortName} dihapus" else "Gagal menghapus file",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ---------- Generate ----------

    private fun refreshGen() {
        val ready = model().downloaded
        b.btnGenerate.isEnabled = ready && !generating
        b.btnGenerate.alpha = if (ready && !generating) 1f else 0.45f
        if (!generating) {
            b.btnCancelGen.visibility = View.GONE
            b.genProgress.visibility = View.GONE
            if (genNote != null) {
                b.tvGenStatus.visibility = View.VISIBLE
                b.tvGenStatus.text = genNote
                b.tvGenStatus.setTextColor(
                    ContextCompat.getColor(this, if (genNoteError) R.color.red else R.color.yellow)
                )
            } else {
                b.tvGenStatus.visibility = View.GONE
            }
        }
    }

    private fun ensureHandle(file: File): Long {
        if (sdHandle != 0L && handleFor == file.absolutePath) return sdHandle
        if (sdHandle != 0L) {
            runCatching { StableDiffusionBridge.freeContext(sdHandle) }
            sdHandle = 0L
        }
        // Maks 4 thread: sampling full-CPU memicu watchdog CPU_HUNG +
        // thermal-throttle di HP; 4 thread titik manis performa vs panas.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        sdHandle = StableDiffusionBridge.loadContext(file.absolutePath, threads)
        handleFor = if (sdHandle != 0L) file.absolutePath else ""
        return sdHandle
    }

    private fun startGenerate() {
        if (generating) {
            Toast.makeText(this, "Tunggu gambar selesai dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val file = selectedFile()
        if (file == null) {
            Toast.makeText(this, "Download model dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = b.etPrompt.text.toString().trim()
        if (prompt.isEmpty()) {
            Toast.makeText(this, "Isi prompt dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val negative = b.etNegative.text.toString().trim()
        val (w, h) = SIZES[b.spSize.selectedItemPosition]
        val steps = b.etSteps.text.toString().toIntOrNull()?.coerceIn(1, 150) ?: 6
        val cfg = b.etCfg.text.toString().toFloatOrNull()?.takeIf { it > 0 } ?: 2.0f
        val sampler = StableDiffusionBridge.SAMPLERS[b.spSampler.selectedItemPosition]
        val seedReq = b.etSeed.text.toString().trim().toLongOrNull() ?: -1L

        generating = true
        genNote = null
        genNoteError = false
        refreshGen()
        b.btnCancelGen.visibility = View.VISIBLE
        b.genProgress.visibility = View.VISIBLE
        b.genProgress.progress = 0
        b.tvGenStatus.visibility = View.VISIBLE
        b.tvGenStatus.setTextColor(ContextCompat.getColor(this, R.color.yellow))
        b.tvGenStatus.text = "Loading model..."

        val imagesDir = File(getExternalFilesDir(null), "images").apply { mkdirs() }
        val out = File(imagesDir, "sd_${System.currentTimeMillis()}.png")

        scope.launch {
            var info = ""
            try {
                val hd = withContext(Dispatchers.IO) { ensureHandle(file) }
                if (hd == 0L) throw IllegalStateException("loadModel gagal (RAM kurang?)")
                withContext(Dispatchers.Main) { b.tvGenStatus.text = "Sampling..." }
                val t0 = System.currentTimeMillis()
                val rc = withContext(Dispatchers.IO) {
                    StableDiffusionBridge.generate(
                        hd, prompt, negative, w, h, steps, cfg, sampler, seedReq, out.absolutePath,
                        StableDiffusionBridge.ProgressCallback { step, total ->
                            handler.post {
                                if (total > 0) {
                                    b.genProgress.progress = (100 * step / total).coerceIn(0, 100)
                                    b.tvGenStatus.text = "Step $step/$total"
                                }
                            }
                        }
                    )
                }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                when (rc) {
                    0 -> {
                        lastOut = out
                        val bmp = BitmapFactory.decodeFile(out.absolutePath)
                        if (bmp != null) {
                            b.ivResult.setImageBitmap(bmp)
                            b.ivResult.visibility = View.VISIBLE
                            info = "${out.name} · ${w}×${h} · $steps steps · $sampler · ${"%.1f".format(secs)}s"
                            b.tvResultInfo.text = info
                            b.tvResultInfo.visibility = View.VISIBLE
                            b.btnShare.visibility = View.VISIBLE
                            Toast.makeText(this@ImageGenActivity, "Tersimpan: ${out.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            out.delete()
                            throw IllegalStateException("PNG rusak")
                        }
                    }
                    -2 -> {
                        genNote = "Generate dibatalkan"
                        genNoteError = false
                        Toast.makeText(this@ImageGenActivity, "Generate dibatalkan", Toast.LENGTH_SHORT).show()
                    }
                    -3 -> {
                        genNote = "Gagal menyimpan PNG (penyimpanan penuh?)"
                        genNoteError = true
                        Toast.makeText(this@ImageGenActivity, "Gagal menyimpan PNG", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        genNote = "Generate gagal (kode $rc) — coba 256px / tutup aplikasi lain"
                        genNoteError = true
                        Toast.makeText(this@ImageGenActivity, "Generate gagal", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                genNote = "Error: ${e.message}"
                genNoteError = true
                Toast.makeText(this@ImageGenActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            generating = false
            refreshGen()
        }
    }

    private fun cancelGenerate() {
        if (!generating) return
        runCatching { StableDiffusionBridge.cancel(sdHandle) }
        b.tvGenStatus.text = "Membatalkan..."
        Toast.makeText(this, "Membatalkan generate...", Toast.LENGTH_SHORT).show()
    }

    private fun shareLast() {
        val f = lastOut?.takeIf { it.exists() } ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share gambar"))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { if (sdHandle != 0L) StableDiffusionBridge.freeContext(sdHandle) }
        super.onDestroy()
    }
}
