package com.llamacpp.local

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.llamacpp.local.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var modelAdapter: ModelAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var selectedModel = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupChat()
        setupSuggestions()
        setupInput()
        setupDrawer()
        updateModelLabel()
    }

    // ---------- Chat ----------
    private fun setupChat() {
        chatAdapter = ChatAdapter(onRetry = { generate() })
        b.rvChat.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.rvChat.adapter = chatAdapter
        chatAdapter.add(ChatMessage("Hey! How can I help?", false), b.rvChat)
    }

    private fun setupSuggestions() {
        b.rvSuggest.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        b.rvSuggest.adapter = SuggestAdapter(onPick = { send(it) })
    }

    private fun setSuggestionsVisible(v: Boolean) {
        val vis = if (v) android.view.View.VISIBLE else android.view.View.GONE
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
            if (t.isNotEmpty()) send(t)
        }
        b.btnPlus.setOnClickListener { toast("Attachments coming soon") }
        b.btnMic.setOnClickListener { toast("Voice input coming soon") }
        refreshSend()
    }

    private fun refreshSend() {
        val active = b.etInput.text.toString().isNotBlank()
        b.btnSend.setBackgroundResource(
            if (active) R.drawable.btn_circle_white else R.drawable.btn_circle)
        b.btnSend.setImageResource(
            if (active) R.drawable.ic_send else R.drawable.ic_send_dim)
    }

    private fun send(text: String) {
        chatAdapter.add(ChatMessage(text, true), b.rvChat)
        b.etInput.text.clear()
        setSuggestionsVisible(false)
        generate()
    }

    private fun generate() {
        chatAdapter.add(ChatMessage("", false, isTyping = true), b.rvChat)
        handler.postDelayed({
            chatAdapter.removeLastTyping()
            chatAdapter.add(ChatMessage(DummyData.nextResponse(), false), b.rvChat)
        }, 1500)
    }

    private fun newChat() {
        chatAdapter.items.clear()
        chatAdapter.notifyDataSetChanged()
        chatAdapter.add(ChatMessage("Hey! How can I help?", false), b.rvChat)
        setSuggestionsVisible(true)
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

        // Chips kategori (wrap otomatis ala flex-wrap)
        DummyData.categories.forEachIndexed { i, cat ->
            val chip = Chip(this).apply {
                text = cat
                isCheckable = true
                isChecked = i == 0
                setChipBackgroundColorResource(R.color.chip_bg)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text))
                setOnClickListener {
                    modelAdapter.filter(cat)
                }
            }
            b.chipCategories.addView(chip)
        }

        modelAdapter = ModelAdapter(
            selectedPos = selectedModel,
            onSelect = { real ->
                selectedModel = real
                modelAdapter.setSelected(real)
                updateModelLabel()
                b.drawerLayout.closeDrawer(Gravity.START)
                toast("Switched to ${DummyData.models[real].shortName}")
            },
            onDownload = { real -> startDownload(real) }
        )
        b.rvModels.layoutManager = LinearLayoutManager(this)
        b.rvModels.adapter = modelAdapter
    }

    private fun updateModelLabel() {
        b.tvModelName.text = DummyData.models[selectedModel].shortName
    }

    private fun startDownload(real: Int) {
        val m = DummyData.models[real]
        if (m.downloading || m.downloaded) return
        m.downloading = true
        m.progress = 0
        modelAdapter.notifyDataSetChanged()
        val tick = object : Runnable {
            override fun run() {
                if (!m.downloading) return
                m.progress += 5
                if (m.progress >= 100) {
                    m.progress = 100
                    m.downloading = false
                    m.downloaded = true
                    toast("${m.shortName} downloaded")
                } else {
                    handler.postDelayed(this, 120)
                }
                modelAdapter.notifyDataSetChanged()
            }
        }
        handler.postDelayed(tick, 120)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
