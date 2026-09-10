package com.llamacpp.local

import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.llamacpp.local.databinding.ActivityMainBinding
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var convAdapter: ConversationAdapter
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val db by lazy { ChatDb(this) }

    private var convId: Long = -1
    private var selectedModel = 0
    private var generating = false
    private var streamPos = -1
    private var lastSendMs = 0L

    // Cache model native yang sedang aktif (load sekali, pakai berkali-kali).
    private var modelHandle = 0L
    private var handleFor = ""

    // Sampling dari Settings (fallback = default bila belum diubah).
    private fun pTemperature() = db.getInt("temperature", 70) / 100f
    private fun pTopK() = db.getInt("top_k", 40).coerceAtLeast(1)
    private fun pTopP() = (db.getInt("top_p", 95) / 100f).coerceIn(0.01f, 1f)
    private fun pMaxTokens() = db.getInt("max_tokens", 96) + 32

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
        refreshConvList()
    }

    override fun onResume() {
        super.onResume()
        // File bisa berubah saat app di background (download/hapus dari halaman Models).
        scanDownloaded()
        // Handle basi (file model aktif sudah dihapus) -> lepas agar load ulang.
        if (modelHandle != 0L && handleFor.isNotEmpty() &&
            !java.io.File(handleFor).exists()
        ) {
            runCatching { LlamaBridge.unloadModel(modelHandle) }
            modelHandle = 0L
            handleFor = ""
        }
        updateModelLabel()
        refreshBanner()
        refreshConvList()
    }

    // ---------- Session & SQLite ----------
    // Tiap New Chat = conversation baru = session + context window baru.
    private fun openLastOrNew() {
        val last = db.lastConversation()
        if (last != null) {
            openConversation(last.first, silent = true)
            if (chatAdapter.items.isNotEmpty()) return
        }
        newConversation()
    }

    private fun openConversation(id: Long, silent: Boolean = false) {
        convId = id
        chatAdapter.items.clear()
        db.messages(id).forEach { (role, text) ->
            chatAdapter.items.add(ChatMessage(text, role == "user"))
        }
        chatAdapter.notifyDataSetChanged()
        setSuggestionsVisible(chatAdapter.items.size <= 1)
        refreshConvList()
        if (!silent) {
            b.drawerLayout.closeDrawer(Gravity.START)
            if (chatAdapter.items.isNotEmpty()) b.rvChat.scrollToPosition(chatAdapter.items.size - 1)
        }
    }

    // Layar chat kosong TANPA baris DB (list boleh kosong total).
    // Baris conversation baru dibuat saat pesan pertama dikirim.
    private fun newConversation() {
        convId = -1
        chatAdapter.items.clear()
        chatAdapter.notifyDataSetChanged()
        chatAdapter.add(ChatMessage("Hey! How can I help?", false), b.rvChat)
        setSuggestionsVisible(true)
        refreshConvList()
    }

    // Pastikan ada baris conversation aktif; kembalikan id-nya.
    private fun ensureConversation(): Long {
        if (convId < 0) {
            val m = AppData.models[selectedModel]
            convId = db.newConversation(m.name, m.quant, m.mobileCtx)
            refreshConvList()
        }
        return convId
    }

    private fun deleteConversation(id: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hapus chat?")
            .setMessage("Conversation dan semua pesannya dihapus permanen.")
            .setPositiveButton("Hapus") { _, _ ->
                db.deleteConversation(id)
                if (id == convId) newConversation() else refreshConvList()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun refreshConvList() {
        if (!::convAdapter.isInitialized) return
        convAdapter.submit(db.listConversations(), convId)
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
            if (generating) {
                cancelGenerate()
                return@setOnClickListener
            }
            // Anti double-send: abaikan tap < 800ms.
            val now = System.currentTimeMillis()
            if (now - lastSendMs < 800) return@setOnClickListener
            lastSendMs = now
            val t = b.etInput.text.toString().trim()
            if (t.isNotEmpty()) send(t)
        }
        b.btnPlus.setOnClickListener { toast("Attachments coming soon") }
        b.btnMic.setOnClickListener { toast("Voice input coming soon") }
        b.btnOpenModels.setOnClickListener { b.drawerLayout.openDrawer(Gravity.START) }
        refreshSend()
    }

    private fun refreshSend() {
        if (generating) {
            // Mode stop: tombol kirim jadi pembatal.
            b.btnSend.setBackgroundResource(R.drawable.btn_circle_white)
            b.btnSend.setImageResource(R.drawable.ic_stop)
            b.btnSend.isEnabled = true
            return
        }
        val active = b.etInput.text.toString().isNotBlank()
        b.btnSend.setBackgroundResource(
            if (active) R.drawable.btn_circle_white else R.drawable.btn_circle)
        b.btnSend.setImageResource(
            if (active) R.drawable.ic_send else R.drawable.ic_send_dim)
    }

    private fun send(text: String) {
        ensureConversation()
        chatAdapter.add(ChatMessage(text, true), b.rvChat)
        db.addMessage(convId, "user", text)
        if (chatAdapter.items.count { !it.isTyping } == 2) {
            db.rename(convId, text.take(40)) // judul = pesan pertama
        }
        b.etInput.text.clear()
        setSuggestionsVisible(false)
        refreshConvList()
        generate()
    }

    private fun activeModel() = AppData.models[selectedModel]

    // File lokal siap pakai untuk (model, quant); null = wajib download dulu.
    private fun modelFile(m: AiModel): java.io.File? =
        ModelDownloader.readyFile(this, m.name, m.quant)

    private fun hasReadyModel() = AppData.models.any { modelFile(it) != null }

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
        ensureConversation()
        val m = activeModel()
        val file = modelFile(m)
        if (file == null) {
            warnNeedModel(m)
            return
        }
        generating = true
        streamPos = -1
        refreshSend()
        showTyping()
        scope.launch {
            val sb = StringBuilder()
            var aiPos = -1
            try {
                val history = promptHistory()
                val h = ensureHandle(m, file)
                if (h == 0L) throw IllegalStateException("loadModel gagal")
                hideTyping()
                chatAdapter.add(ChatMessage("", false), b.rvChat)
                aiPos = chatAdapter.items.size - 1
                streamPos = aiPos
                LlamaBridge.generateFlow(
                    h, history, pMaxTokens(), pTemperature(), pTopK(), pTopP()
                ).collect { tok ->
                    if (aiPos < 0 || aiPos >= chatAdapter.items.size) return@collect
                    sb.append(tok)
                    chatAdapter.items[aiPos] = ChatMessage(sb.toString(), false)
                    chatAdapter.notifyItemChanged(aiPos)
                    b.rvChat.scrollToPosition(aiPos)
                }
            } catch (e: Exception) {
                if (aiPos < 0) {
                    hideTyping()
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
            streamPos = -1
            refreshSend()
        }
    }

    // Animasi loading: "Thinking.", "..", "..." berputar selama pending.
    private val typingAnim = object : Runnable {
        var dots = 0
        override fun run() {
            val i = chatAdapter.items.indexOfFirst { it.isTyping }
            if (i < 0) return
            dots = (dots + 1) % 4
            chatAdapter.items[i] =
                ChatMessage("Thinking" + ".".repeat(dots), false, isTyping = true)
            chatAdapter.notifyItemChanged(i)
            handler.postDelayed(this, 450)
        }
    }

    private fun showTyping() {
        chatAdapter.add(ChatMessage("Thinking", false, isTyping = true), b.rvChat)
        typingAnim.dots = 0
        handler.post(typingAnim)
    }

    private fun hideTyping() {
        handler.removeCallbacks(typingAnim)
        chatAdapter.removeLastTyping()
    }

    // Batalkan generate yang sedang jalan (tombol Stop).
    private fun cancelGenerate() {
        if (!generating) return
        runCatching { LlamaBridge.cancel() }
        handler.postDelayed({
            if (!generating) return@postDelayed // sudah selesai sendiri
            if (streamPos >= 0 && streamPos < chatAdapter.items.size) {
                val partial = chatAdapter.items[streamPos].text
                if (partial.isNotBlank()) db.addMessage(convId, "assistant", "$partial\n[dibatalkan]")
            }
            generating = false
            streamPos = -1
            refreshSend()
            toast("Generate dibatalkan")
        }, 300)
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

    // Peringatan bila model aktif belum diunduh + arahkan ke halaman Models.
    private fun warnNeedModel(m: AiModel) {
        chatAdapter.add(
            ChatMessage(
                "Model \"${m.shortName}\" (${m.quant}) belum diunduh.\n\n" +
                "Buka halaman Models → tap Get untuk download dulu.",
                false
            ),
            b.rvChat
        )
        toast("Download model dulu sebelum chat")
        handler.postDelayed({
            startActivity(Intent(this, ModelsActivity::class.java))
        }, 600)
    }

    private fun newChat() {
        if (generating) return
        newConversation()
    }

    // ---------- Drawer: menu + list conversation ----------
    private fun setupDrawer() {
        b.btnMenu.setOnClickListener { b.drawerLayout.openDrawer(Gravity.START) }
        b.modelPill.setOnClickListener { showModelPicker() }
        b.btnCloseDrawer.setOnClickListener { b.drawerLayout.closeDrawer(Gravity.START) }
        b.btnNewChat.setOnClickListener { newChat() }
        b.btnNewChatDrawer.setOnClickListener {
            newChat()
            b.drawerLayout.closeDrawer(Gravity.START)
        }
        b.btnModels.setOnClickListener {
            b.drawerLayout.closeDrawer(Gravity.START)
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        convAdapter = ConversationAdapter(
            onOpen = { id -> openConversation(id) },
            onDelete = { id -> deleteConversation(id) }
        )
        b.rvConversations.layoutManager = LinearLayoutManager(this)
        b.rvConversations.adapter = convAdapter
    }

    private fun updateModelLabel() {
        val m = AppData.models[selectedModel]
        b.tvModelName.text = "${m.shortName} · ${m.quant}"
    }

    // Dropdown model yang sudah terdownload/aktif (tap pill atas).
    private fun showModelPicker() {
        val ready = AppData.models.mapIndexedNotNull { i, m ->
            if (modelFile(m) != null) i else null
        }
        if (ready.isEmpty()) {
            toast("Belum ada model — download dulu")
            startActivity(Intent(this, ModelsActivity::class.java))
            return
        }
        val labels = ready.map { "${AppData.models[it].shortName} · ${AppData.models[it].quant}" }.toTypedArray()
        val checked = ready.indexOf(selectedModel).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Pilih model")
            .setSingleChoiceItems(labels, checked) { d, which ->
                val real = ready[which]
                db.set("active_model", AppData.models[real].name)
                selectedModel = real
                updateModelLabel()
                toast("Active: ${AppData.models[real].shortName}")
                d.dismiss()
            }
            .setNeutralButton("Models") { _, _ ->
                startActivity(Intent(this, ModelsActivity::class.java))
            }
            .show()
    }

    // Flags + model aktif (dari halaman Models). Dipanggil saat start/resume.
    private fun scanDownloaded() {
        ModelDownloader.scanDisk(this)
        AppData.models.forEach { m ->
            m.quant = ModelDownloader.savedQuant(this, m.name)
            m.downloaded = ModelDownloader.readyFile(this, m.name, m.quant) != null
            if (!m.downloaded) m.downloading = false
        }
        val saved = db.get("active_model", null)
        val idx = AppData.models.indexOfFirst { it.name == saved }
        selectedModel = when {
            idx >= 0 -> idx
            else -> AppData.models.indexOfFirst { it.downloaded }.takeIf { it >= 0 } ?: 0
        }
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
