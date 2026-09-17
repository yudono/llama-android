package com.llamacpp.local

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.llamacpp.local.databinding.ActivityModelsBinding
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Halaman daftar model: filter kategori, pilih quant, download (HF),
// pakai (jadi aktif), hapus permanen (file dihapus, entri kembali GET).
class ModelsActivity : AppCompatActivity() {

    private lateinit var b: ActivityModelsBinding
    private lateinit var adapter: ModelAdapter
    private lateinit var db: ChatDb
    private val handler = Handler(Looper.getMainLooper())
    private var selectedModel = 0
    // Pembatalan salinan file lokal per model (pick); unduhan HF dibatalkan
    // via DownloadManager (lihat cancelTransfer).
    private val copyCancels = mutableMapOf<Int, AtomicBoolean>()
    private var pickFor = -1

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickFor >= 0) startCopy(pickFor, uri)
        pickFor = -1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        b = ActivityModelsBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = ChatDb(this)

        b.btnBack.setOnClickListener { finish() }

        val cats = AppData.categories
        val catAdapter = ArrayAdapter(this, R.layout.item_quant, cats)
        catAdapter.setDropDownViewResource(R.layout.item_quant_dropdown)
        b.spCategory.adapter = catAdapter
        b.spCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                adapter.filter(cats[i])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        selectedModel = activeIndex()
        adapter = ModelAdapter(
            selectedPos = selectedModel,
            onSelect = { real -> switchModel(real); finish() },
            onDownload = { real -> startDownload(real) },
            onCancel = { real -> cancelTransfer(real) },
            onPick = { real -> launchPicker(real) },
            onQuantChange = { real, q -> changeQuant(real, q) },
            onDelete = { real -> confirmDelete(real) }
        )
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvModels.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refreshFlags()
        selectedModel = activeIndex()
        adapter.setSelected(selectedModel)
    }

    private fun activeIndex(): Int {
        val saved = db.get("active_model", null)
        val i = AppData.models.indexOfFirst { it.name == saved }
        return if (i >= 0) i else 0
    }

    private fun refreshFlags() {
        ModelDownloader.scanDisk(this)
        AppData.models.forEach { m ->
            m.quant = ModelDownloader.savedQuant(this, m.name)
            m.downloaded = ModelDownloader.readyFile(this, m.name, m.quant) != null
            if (!m.downloaded) m.downloading = false
        }
        adapter.notifyDataSetChanged()
    }

    private fun switchModel(real: Int) {
        db.set("active_model", AppData.models[real].name)
        adapter.setSelected(real)
        Toast.makeText(this, "Active: ${AppData.models[real].shortName}", Toast.LENGTH_SHORT).show()
    }

    private fun changeQuant(real: Int, quant: String) {
        val m = AppData.models[real]
        m.quant = quant
        m.downloading = false
        m.progress = 0f
        ModelDownloader.saveQuant(this, m.name, quant)
        m.downloaded = ModelDownloader.readyFile(this, m.name, quant) != null
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(real: Int) {
        val m = AppData.models[real]
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus model?")
            .setMessage("${m.shortName} (${m.quant}) dihapus permanen dari penyimpanan. Entrinya kembali jadi GET.")
            .setPositiveButton("Hapus") { _, _ -> deleteModel(real) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteModel(real: Int) {
        val m = AppData.models[real]
        val fn = ModelDownloader.savedFilename(this, m.name, m.quant)
        val f = if (fn != null) ModelDownloader.storedFile(this, fn) else null
        val ok = f?.let { runCatching { it.delete() }.getOrDefault(false) } ?: false
        ModelDownloader.saveFilename(this, m.name, m.quant, "")
        ModelDownloader.saveTotal(this, m.name, m.quant, 0L)
        m.downloaded = false
        m.downloading = false
        m.progress = 0f
        adapter.notifyDataSetChanged()
        Toast.makeText(
            this,
            if (ok || f == null) "${m.shortName} dihapus" else "Gagal menghapus file",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startDownload(real: Int) {
        val m = AppData.models[real]
        m.infoUrl?.let { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "Tak bisa membuka link", Toast.LENGTH_SHORT).show()
            }
            return
        }
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
        if (m.downloading || ModelDownloader.readyFile(this, m.name, m.quant) != null) {
            if (ModelDownloader.readyFile(this, m.name, m.quant) != null) {
                m.downloaded = true
                adapter.notifyDataSetChanged()
            }
            return
        }
        ModelDownloader.savedFilename(this, m.name, m.quant)?.let { fn ->
            ModelDownloader.storedFile(this, fn)?.takeIf { it.exists() }?.delete()
        }
        m.downloading = true
        m.progress = 0f
        adapter.notifyDataSetChanged()
        Thread {
            try {
                var fn = ModelDownloader.savedFilename(this, m.name, m.quant)
                if (fn.isNullOrBlank()) {
                    val r = ModelDownloader.resolve(this, m.repo, m.quant)
                    if (r.file == null) {
                        val msg = when {
                            r.needToken -> "Isi HF token di Settings dulu"
                            !r.reached -> "Periksa koneksi internet, lalu coba lagi"
                            else -> "Tak ada file GGUF yang cocok untuk model ini"
                        }
                        handler.post {
                            m.downloading = false
                            adapter.notifyDataSetChanged()
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                        return@Thread
                    }
                    if (r.fellBack && r.quant != m.quant) {
                        m.quant = r.quant
                        ModelDownloader.saveQuant(this, m.name, r.quant)
                        handler.post {
                            adapter.notifyDataSetChanged()
                            Toast.makeText(this, "${m.shortName}: pakai ${r.quant}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    fn = r.file
                    ModelDownloader.saveFilename(this, m.name, m.quant, fn)
                }
                val id = ModelDownloader.enqueue(
                    this, ModelDownloader.downloadUrl(m.repo, fn),
                    fn, ModelDownloader.getToken(this)
                )
                m.downloadId = id
                pollDownload(real, id)
            } catch (e: Exception) {
                handler.post {
                    m.downloading = false
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Download gagal: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun cancelTransfer(real: Int) {
        if (AppData.models[real].downloadId > 0) cancelDownload(real)
        else cancelCopy(real)
    }

    private fun cancelDownload(real: Int) {
        val m = AppData.models[real]
        if (m.downloadId > 0) {
            ModelDownloader.remove(this, m.downloadId)
        }
        m.downloading = false
        m.progress = 0f
        m.downloadId = -1L
        adapter.notifyDataSetChanged()
        Toast.makeText(this, "Download dibatalkan", Toast.LENGTH_SHORT).show()
    }

    private fun cancelCopy(real: Int) {
        copyCancels[real]?.set(true)
    }

    // Pilih file .gguf sendiri (mis. hasil adb push ke Download) sbg alternatif Get.
    private fun launchPicker(real: Int) {
        val m = AppData.models[real]
        if (m.downloading || m.downloaded) return
        pickFor = real
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            pickFor = -1
            Toast.makeText(this, "Tak ada file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCopy(real: Int, uri: Uri) {
        val m = AppData.models[real]
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
        adapter.notifyDataSetChanged()
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
                                handler.post { adapter.notifyDataSetChanged() }
                            }
                        }
                    }
                } ?: throw IllegalStateException("tak bisa buka file")
                if (done <= 0) throw IllegalStateException("file kosong")
                ModelDownloader.saveFilename(this, m.name, m.quant, safe)
                ModelDownloader.saveTotal(this, m.name, m.quant, done)
                ModelDownloader.saveQuant(this, m.name, m.quant)
                handler.post {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100f
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "${m.shortName} terpasang", Toast.LENGTH_SHORT).show()
                }
            } catch (e: java.util.concurrent.CancellationException) {
                dest.delete()
                handler.post {
                    m.downloading = false
                    m.progress = 0f
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Penyalinan dibatalkan", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                dest.delete()
                handler.post {
                    m.downloading = false
                    m.progress = 0f
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this, "Gagal menyalin: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                copyCancels.remove(real)
            }
        }.start()
    }

    private fun pollDownload(real: Int, id: Long) {
        val m = AppData.models[real]
        val tick = object : Runnable {
            override fun run() {
                val p = ModelDownloader.query(this@ModelsActivity, id)
                if (p == null || p.status == android.app.DownloadManager.STATUS_FAILED) {
                    m.downloading = false
                    handler.post {
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this@ModelsActivity, "Download gagal", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                if (p.total > 0) ModelDownloader.saveTotal(this@ModelsActivity, m.name, m.quant, p.total)
                if (p.status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100f
                    handler.post {
                        adapter.notifyDataSetChanged()
                        Toast.makeText(this@ModelsActivity, "${m.shortName} ${m.quant} downloaded", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                if (p.total > 0) m.progress = (100f * p.done / p.total).coerceIn(0f, 100f)
                handler.post { adapter.notifyDataSetChanged() }
                handler.postDelayed(this, 800)
            }
        }
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
