package com.llamacpp.local

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
        private const val TYPE_TYPING = 2
    }

    val items = mutableListOf<ChatMessage>()

    override fun getItemViewType(position: Int): Int = when {
        items[position].isTyping -> TYPE_TYPING
        items[position].isUser -> TYPE_USER
        else -> TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserVH(inf.inflate(R.layout.item_message_user, parent, false))
            TYPE_TYPING -> TypingVH(inf.inflate(R.layout.item_message_ai, parent, false))
            else -> AiVH(inf.inflate(R.layout.item_message_ai, parent, false), onRetry)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        when (holder) {
            is UserVH -> holder.text.text = msg.text
            is AiVH -> holder.text.text = msg.text
            is TypingVH -> holder.text.text = "Thinking…"
        }
    }

    override fun getItemCount(): Int = items.size

    fun add(msg: ChatMessage, rv: RecyclerView) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
        rv.scrollToPosition(items.size - 1)
    }

    fun removeLastTyping() {
        val i = items.indexOfLast { it.isTyping }
        if (i >= 0) {
            items.removeAt(i)
            notifyItemRemoved(i)
        }
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tv_text)
    }

    class AiVH(v: View, onRetry: () -> Unit) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tv_text)
        init {
            v.findViewById<View>(R.id.btn_retry).setOnClickListener { onRetry() }
            v.findViewById<View>(R.id.btn_copy).setOnClickListener { /* dummy */ }
            v.findViewById<View>(R.id.btn_speak).setOnClickListener { /* dummy */ }
        }
    }

    class TypingVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.tv_text)
        init {
            // Sembunyikan tombol aksi saat mengetik
            (v.findViewById<View>(R.id.btn_copy).parent as View).visibility = View.GONE
        }
    }
}
