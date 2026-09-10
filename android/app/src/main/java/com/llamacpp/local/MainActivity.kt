package com.llamacpp.local

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.llamacpp.local.databinding.ActivityMainBinding
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var modelAdapter: ModelAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val db by lazy { ChatDb(this) }

    private var convId: Long = -1
    private var selectedModel = 0
    private var generating = false

    // Cache model native yang sedang aktif (load sekali, pakai berkali-kali).
    private var modelHandle = 0L
    private var handleFor = ""

    // Sampling dari Settings (fallback = default bila belum diubah).
    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)
    private fun pTemperature() = prefs().getInt("temperature", 70) / 100f
    private fun pTopK() = prefs().getInt("top_k", 40).coerceAtLeast(1)
    private fun pTopP() = (prefs().getInt("top_p", 95) / 100f).coerceIn(0.01f, 1f)
    private fun pMaxTokens() = prefs().getInt("max_tokens", 96) + 32

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        scanDownloaded()
        setupChat()
        setupSuggestions()
        setupInput()
        setupDrawer()
        openLastOrNew()
        updateModelLabel()
        refreshBanner()
    }

    override fun onResume() {
        super.onResume()
        // File bisa berubah saat app di background (DownloadManager jalan terus).
        scanDownloaded()
        modelAdapter.notifyDataSetChanged()
        refreshBanner()
    }

    // ---------- Session & SQLite ----------
    // Tiap New Chat = conversation baru = session + context window baru.
    private fun openLastOrNew() {
        val last = db.lastConversation()
        if (last != null) {
            convId = last.first
            val saved = db.messages(convId)
            if (saved.isNotEmpty()) {
                saved.forEach { (role, text) ->
                    chatAdapter.add(ChatMessage(text, role == "user"), b.rvChat)
                }
                setSuggestionsVisible(false)
                return
            }
        }
        newConversation()
    }

    private fun newConversation() {
        val m = DummyData.models[selectedModel]
        convId = db.newConversation(m.name, m.quant, m.mobileCtx)
        chatAdapter.items.clear()
        chatAdapter.notifyDataSetChanged()
        val greet = ChatMessage("Hey! How can I help?", false)
        chatAdapter.add(greet, b.rvChat)
        db.addMessage(convId, "assistant", greet.text)
        setSuggestionsVisible(true)
    }

    // Riwayat untuk prompt: system + maksimal 8 pesan terakhir.
    private fun promptHistory(): List<Pair<String, String>> {
        val all = db.messages(convId).takeLast(8).toMutableList()
        all.add(0, "system" to "You are a helpful assistant. Answer briefly.")
        return all
    }

    // ---------- Chat ----------
    private fun setupChat() {
        chatAdapter = ChatAdapter(onRetry = { regenerate() })
        b.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.rvChat.adapter = chatAdapter
    }

    private fun setupSuggestions() {
        b.rvSuggest.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvSuggest.adapter = SuggestAdapter(onPick = { send(it) })
    }

    private fun setSuggestionsVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        b.rvSuggest.visibility = vis
        b.tvSuggestLabel.visibility = vis
    }

    // ---------- Input ----------
    private fun setupInput() {
        b.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) = refreshSend()
            override fun afterTextChanged(s: Editable?) {}
        })
        b.btnSend.setOnClickListener {
            val t = b.etInput.text.toString().trim()
            if (t.isNotEmpty() && !generating) send(t)
        }
        b.btnPlus.setOnClickListener { toast("Attachments coming soon") }
        b.btnMic.setOnClickListener { toast("Voice input coming soon") }
        b.btnOpenModels.setOnClickListener { b.drawerLayout.openDrawer(Gravity.START) }
        refreshSend()
    }

    private fun refreshSend() {
        val active = b.etInput.text.toString().isNotBlank() && !generating
        b.btnSend.setBackgroundResource(
            if (active) R.drawable.btn_circle_white else R.drawable.btn_circle)
        b.btnSend.setImageResource(
            if (active) R.drawable.ic_send else R.drawable.ic_send_dim)
    }

    private fun send(text: String) {
        chatAdapter.add(ChatMessage(text, true), b.rvChat)
        db.addMessage(convId, "user", text)
        if (chatAdapter.items.count { !it.isTyping } == 2) {
            db.rename(convId, text.take(40)) // judul = pesan pertama
        }
        b.etInput.text.clear()
        setSuggestionsVisible(false)
        generate()
    }

    private fun activeModel() = DummyData.models[selectedModel]

    // File lokal untuk (model, quant) aktif; null = wajib download dulu.
    private fun modelFile(m: AiModel): java.io.File? {
        val fn = ModelDownloader.savedFilename(this, m.name, m.quant) ?: return null
        val f = ModelDownloader.localFile(this, fn)
        if (!f.exists()) return null
        val expected = ModelDownloader.savedTotal(this, m.name, m.quant)
        // Tolak file parsial (mis. sisa download sesi sebelumnya).
        if (expected > 0 && f.length() < expected) return null
        return if (f.length() > 0) f else null
    }

    private fun hasReadyModel() = DummyData.models.any { modelFile(it) != null }

    private fun refreshBanner() {
        b.bannerNeedModel.visibility = if (hasReadyModel()) View.GONE else View.VISIBLE
    }

    private fun ensureHandle(m: AiModel, file: java.io.File): Long {
        if (modelHandle != 0L && handleFor == file.absolutePath) return modelHandle
        if (modelHandle != 0L) {
            runCatching { LlamaBridge.unloadModel(modelHandle) }
            modelHandle = 0L
        }
        modelHandle = LlamaBridge.loadModel(file.absolutePath, m.mobileCtx)
        handleFor = if (modelHandle != 0L) file.absolutePath else ""
        return modelHandle
    }

    private fun generate() {
        if (generating) return
        val m = activeModel()
        val file = modelFile(m)
        if (file == null) {
            warnNeedModel(m)
            return
        }
        generating = true
        refreshSend()
        chatAdapter.add(ChatMessage("", false, isTyping = true), b.rvChat)
        val maxTokens = pMaxTokens()
        val temp = pTemperature()
        val topK = pTopK()
        val topP = pTopP()
        scope.launch {
            val sb = StringBuilder()
            var aiPos = -1
            try {
                val history = promptHistory()
                val h = ensureHandle(m, file)
                if (h == 0L) throw IllegalStateException("loadModel gagal")
                chatAdapter.removeLastTyping()
                chatAdapter.add(ChatMessage("", false), b.rvChat)
                aiPos = chatAdapter.items.size - 1
                LlamaBridge.generateFlow(h, history, maxTokens, temp, topK, topP).collect { tok ->
                    sb.append(tok)
                    chatAdapter.items[aiPos] = ChatMessage(sb.toString(), false)
                    chatAdapter.notifyItemChanged(aiPos)
                    b.rvChat.scrollToPosition(aiPos)
                }
            } catch (e: Exception) {
                if (aiPos < 0) {
                    chatAdapter.removeLastTyping()
                    chatAdapter.add(ChatMessage("Error: ${e.message}", false), b.rvChat)
                    aiPos = chatAdapter.items.size - 1
                } else {
                    val cur = chatAdapter.items[aiPos].text
                    chatAdapter.items[aiPos] =
                        ChatMessage("$cur\n[error: ${e.message}]", false)
                    chatAdapter.notifyItemChanged(aiPos)
                }
            }
            val final = if (aiPos >= 0) chatAdapter.items[aiPos].text else ""
            if (final.isNotBlank()) db.addMessage(convId, "assistant", final)
            generating = false
            refreshSend()
        }
    }

    private fun regenerate() {
        if (generating) return
        db.deleteLastMessage(convId, "assistant")
        val i = chatAdapter.items.indexOfLast { !it.isTyping && !it.isUser }
        if (i >= 0) {
            chatAdapter.items.removeAt(i)
            chatAdapter.notifyItemRemoved(i)
        }
        generate()
    }

    // Peringatan bila model aktif belum diunduh + arahkan ke drawer.
    private fun warnNeedModel(m: AiModel) {
        chatAdapter.add(
            ChatMessage(
                "Model \"${m.shortName}\" (${m.quant}) belum diunduh.\n\n" +
                "Buka menu kiri → tap Get pada model untuk download dulu.",
                false
            ),
            b.rvChat
        )
        toast("Download model dulu sebelum chat")
        handler.postDelayed({ b.drawerLayout.openDrawer(Gravity.START) }, 600)
    }

    private fun newChat() {
        if (generating) return
        newConversation()
    }

    // ---------- Drawer & model ----------
    private fun setupDrawer() {
        b.btnMenu.setOnClickListener { b.drawerLayout.openDrawer(Gravity.START) }
        b.modelPill.setOnClickListener { b.drawerLayout.openDrawer(Gravity.START) }
        b.btnCloseDrawer.setOnClickListener { b.drawerLayout.closeDrawer(Gravity.START) }
        b.btnNewChat.setOnClickListener { newChat() }
        b.btnNewChatDrawer.setOnClickListener {
            newChat()
            b.drawerLayout.closeDrawer(Gravity.START)
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Filter kategori = dropdown simpel (ganti chips yang makan tempat).
        val cats = DummyData.categories
        val catAdapter = ArrayAdapter(this, R.layout.item_quant, cats)
        catAdapter.setDropDownViewResource(R.layout.item_quant_dropdown)
        b.spCategory.adapter = catAdapter
        b.spCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                modelAdapter.filter(cats[i])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        modelAdapter = ModelAdapter(
            selectedPos = selectedModel,
            onSelect = { real ->
                switchModel(real)
                b.drawerLayout.closeDrawer(Gravity.START)
            },
            onDownload = { real -> startDownload(real) },
            onQuantChange = { real, q -> changeQuant(real, q) }
        )
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvModels.adapter = modelAdapter
    }

    private fun updateModelLabel() {
        val m = DummyData.models[selectedModel]
        b.tvModelName.text = "${m.shortName} · ${m.quant}"
    }

    private fun switchModel(real: Int) {
        selectedModel = real
        modelAdapter.setSelected(real)
        updateModelLabel()
        toast("Switched to ${DummyData.models[real].shortName}")
    }

    private fun changeQuant(real: Int, quant: String) {
        val m = DummyData.models[real]
        m.quant = quant
        m.downloading = false
        m.progress = 0
        ModelDownloader.saveQuant(this, m.name, quant)
        // Jika file quant ini sudah ada di disk, langsung siap pakai.
        m.downloaded = modelFile(m) != null
        modelAdapter.notifyDataSetChanged()
        if (real == selectedModel) updateModelLabel()
    }

    // Tandai yang file-nya sudah lengkap di disk.
    private fun scanDownloaded() {
        DummyData.models.forEach { m ->
            m.quant = ModelDownloader.savedQuant(this, m.name)
            m.downloaded = modelFile(m) != null
            if (!m.downloaded) m.downloading = false
        }
        val first = DummyData.models.indexOfFirst { it.downloaded }
        if (first >= 0) selectedModel = first
    }

    private fun startDownload(real: Int) {
        val m = DummyData.models[real]
        // Wajib isi HF token dulu sebelum boleh download.
        if (ModelDownloader.getToken(this).isBlank()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Butuh HF token")
                .setMessage(
                    "Download model dari HuggingFace butuh token gratis.\n\n" +
                    "Isi dulu di Settings, lalu tap Get lagi."
                )
                .setPositiveButton("Buka Settings") { _, _ ->
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        if (m.downloading || modelFile(m) != null) {            if (modelFile(m) != null) {
                m.downloaded = true
                modelAdapter.notifyDataSetChanged()
                refreshBanner()
            }
            return
        }
        // Hapus sisa parsial agar tak dikira selesai.
        ModelDownloader.savedFilename(this, m.name, m.quant)?.let { fn ->
            ModelDownloader.localFile(this, fn).takeIf { it.exists() }?.delete()
        }
        m.downloading = true
        m.progress = 0
        modelAdapter.notifyDataSetChanged()
        Thread {
            try {
                var fn = ModelDownloader.savedFilename(this, m.name, m.quant)
                if (fn == null) {
                    val r = ModelDownloader.resolve(this, m.repo, m.quant)
                    if (r.file == null) {
                        val msg = when {
                            r.needToken -> "Isi HF token di Settings dulu"
                            !r.reached -> "Periksa koneksi internet, lalu coba lagi"
                            else -> "Tak ada file GGUF yang cocok untuk model ini"
                        }
                        handler.post {
                            m.downloading = false
                            modelAdapter.notifyDataSetChanged()
                            toast(msg)
                            if (r.needToken) b.drawerLayout.closeDrawer(Gravity.START)
                        }
                        return@Thread
                    }
                    if (r.fellBack && r.quant != m.quant) {
                        m.quant = r.quant
                        ModelDownloader.saveQuant(this, m.name, r.quant)
                        handler.post {
                            modelAdapter.notifyDataSetChanged()
                            updateModelLabel()
                            toast("${m.shortName}: pakai ${r.quant} (lebih ringan/tersedia)")
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
                    modelAdapter.notifyDataSetChanged()
                    toast("Download gagal: ${e.message}")
                }
            }
        }.start()
    }

    private fun pollDownload(real: Int, id: Long) {
        val m = DummyData.models[real]
        val tick = object : Runnable {
            override fun run() {
                val p = ModelDownloader.query(this@MainActivity, id)
                if (p == null || p.status == android.app.DownloadManager.STATUS_FAILED) {
                    m.downloading = false
                    handler.post {
                        modelAdapter.notifyDataSetChanged()
                        toast("Download gagal")
                    }
                    return
                }
                if (p.total > 0) ModelDownloader.saveTotal(this@MainActivity, m.name, m.quant, p.total)
                if (p.status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100
                    handler.post {
                        modelAdapter.notifyDataSetChanged()
                        refreshBanner()
                        toast("${m.shortName} ${m.quant} downloaded")
                    }
                    return
                }
                if (p.total > 0) m.progress = (100 * p.done / p.total).toInt()
                handler.post { modelAdapter.notifyDataSetChanged() }
                handler.postDelayed(this, 800)
            }
        }
        handler.post(tick)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        runCatching { if (modelHandle != 0L) LlamaBridge.unloadModel(modelHandle) }
        super.onDestroy()
    }
}
