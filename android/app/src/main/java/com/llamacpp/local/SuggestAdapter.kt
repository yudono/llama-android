package com.llamacpp.local

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SuggestAdapter(
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<SuggestAdapter.VH>() {

    private val items = DummyData.suggestions

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        h.title.text = items[pos].first
        h.sub.text = items[pos].second
        h.itemView.setOnClickListener { onPick("${items[pos].first} ${items[pos].second}") }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tv_title)
        val sub: TextView = v.findViewById(R.id.tv_subtitle)
    }
}
