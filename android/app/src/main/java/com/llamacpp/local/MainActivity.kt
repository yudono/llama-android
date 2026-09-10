package com.llamacpp.local

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
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

    companion object {
        const val MAX_TOKENS = 128
        const val TEMPERATURE = 0.7f
    }

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
    }

    // ---------- Session & SQLite ----------
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
        convId = db.newConversation(DummyData.models[selectedModel].name)
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

    private fun modelFile(m: AiModel): java.io.File? {
        val fn = ModelDownloader.savedFilename(this, m.name) ?: return null
        val f = ModelDownloader.localFile(this, fn)
        return if (f.exists() && f.length() > 0) f else null
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
        scope.launch {
            val sb = StringBuilder()
            var aiPos = -1
            try {
                val history = promptHistory()
                val h = ensureHandle(m, file)
                if (h == 0L) throw IllegalStateException("loadModel gagal")
                // Ganti typing jadi bubble kosong yang di-streaming.
                chatAdapter.removeLastTyping()
                chatAdapter.add(ChatMessage("", false), b.rvChat)
                aiPos = chatAdapter.items.size - 1
                LlamaBridge.generateFlow(h, history, MAX_TOKENS, TEMPERATURE).collect { tok ->
                    sb.append(tok)
                    chatAdapter.items[aiPos] =
                        ChatMessage(sb.toString(), false)
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
                "Model \"${m.shortName}\" belum diunduh.\n\n" +
                "Buka menu kiri → tap Get pada model untuk download dulu " +
                "(butuh HF token gratis, isi di bagian bawah drawer).",
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

        b.etToken.setText(ModelDownloader.getToken(this))
        b.etToken.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ModelDownloader.setToken(this@MainActivity, s.toString())
            }
        })

        DummyData.categories.forEachIndexed { i, cat ->
            val chip = Chip(this).apply {
                text = cat
                isCheckable = true
                isChecked = i == 0
                setChipBackgroundColorResource(R.color.chip_bg)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text))
                setOnClickListener { modelAdapter.filter(cat) }
            }
            b.chipCategories.addView(chip)
        }

        modelAdapter = ModelAdapter(
            selectedPos = selectedModel,
            onSelect = { real ->
                switchModel(real)
                b.drawerLayout.closeDrawer(Gravity.START)
            },
            onDownload = { real -> startDownload(real) }
        )
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvModels.adapter = modelAdapter
    }

    private fun updateModelLabel() {
        b.tvModelName.text = DummyData.models[selectedModel].shortName
    }

    private fun switchModel(real: Int) {
        selectedModel = real
        modelAdapter.setSelected(real)
        updateModelLabel()
        toast("Switched to ${DummyData.models[real].shortName}")
    }

    // Tandai yang file-nya sudah ada di disk.
    private fun scanDownloaded() {
        DummyData.models.forEach { m ->
            val f = modelFile(m)
            m.downloaded = f != null
        }
        val first = DummyData.models.indexOfFirst { it.downloaded }
        if (first >= 0) selectedModel = first
    }

    private fun startDownload(real: Int) {
        val m = DummyData.models[real]
        if (m.downloading || m.downloaded) return
        m.downloading = true
        m.progress = 0
        modelAdapter.notifyDataSetChanged()
        Thread {
            try {
                var fn = ModelDownloader.savedFilename(this, m.name)
                if (fn == null) {
                    val (resolved, needToken) = ModelDownloader.resolve(this, m.repo, m.quant)
                    if (resolved == null) {
                        handler.post {
                            m.downloading = false
                            modelAdapter.notifyDataSetChanged()
                            toast(if (needToken) "Isi HF token dulu (drawer bawah)" else "File GGUF tak ketemu")
                        }
                        return@Thread
                    }
                    fn = resolved
                    ModelDownloader.saveFilename(this, m.name, fn)
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
                if (p.status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                    m.downloading = false
                    m.downloaded = true
                    m.progress = 100
                    handler.post {
                        modelAdapter.notifyDataSetChanged()
                        toast("${m.shortName} downloaded")
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
