package com.llamacpp.local

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Daftar conversation di drawer: tap = buka, Del = hapus permanen.
class ConversationAdapter(
    private val onOpen: (Long) -> Unit,
    private val onDelete: (Long) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.VH>() {

    var items: List<ChatDb.Conv> = emptyList()
        private set
    var currentId: Long = -1

    private val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    fun submit(list: List<ChatDb.Conv>, current: Long) {
        items = list
        currentId = current
        notifyDataSetChanged()
    }

    private fun shortOf(modelName: String): String =
        AppData.models.find { it.name == modelName }?.shortName ?: modelName

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.title.text = c.title ?: "New chat"
        h.title.setTextColor(
            h.itemView.context.getColor(
                if (c.id == currentId) R.color.accent else R.color.text
            )
        )
        h.sub.text = "${shortOf(c.model)} · ${dateFmt.format(Date(c.createdAt))}"
        h.itemView.setOnClickListener { onOpen(c.id) }
        h.delete.setOnClickListener { onDelete(c.id) }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tv_title)
        val sub: TextView = v.findViewById(R.id.tv_sub)
        val delete: MaterialButton = v.findViewById(R.id.btn_delete)
    }
}
