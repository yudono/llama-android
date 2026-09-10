package com.llamacpp.local

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ModelAdapter(
    private var selectedPos: Int,
    private val onSelect: (Int) -> Unit,
    private val onDownload: (Int) -> Unit
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    var shown: List<AiModel> = DummyData.models
        private set

    fun filter(category: String) {
        shown = if (category == "Semua") DummyData.models
                else DummyData.models.filter { it.category == category }
        notifyDataSetChanged()
    }

    fun realPosition(shownPos: Int): Int = DummyData.models.indexOf(shown[shownPos])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = shown[pos]
        val real = realPosition(pos)
        h.name.text = m.name
        h.meta.text = "${m.paramSize}  |  ${m.fileSize}  |  RAM ${m.ram}"
        h.card.setCardBackgroundColor(
            ContextCompat.getColor(h.itemView.context,
                if (real == selectedPos) R.color.card_selected else R.color.card)
        )
        when {
            m.downloaded -> {
                h.status.text = if (real == selectedPos) "Active" else "Ready to use"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context,
                    if (real == selectedPos) R.color.green else R.color.text_dim))
                h.action.visibility = if (real == selectedPos) View.GONE else View.VISIBLE
                h.action.text = "Use"
                h.action.setOnClickListener { onSelect(real) }
            }
            m.downloading -> {
                h.status.text = "Downloading ${m.progress}%"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.yellow))
                h.action.visibility = View.GONE
            }
            else -> {
                h.status.text = "Not downloaded"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_dim))
                h.action.visibility = View.VISIBLE
                h.action.text = "Get"
                h.action.setOnClickListener { onDownload(real) }
            }
        }
        h.itemView.setOnClickListener { if (m.downloaded) onSelect(real) }
    }

    override fun getItemCount(): Int = shown.size

    fun setSelected(real: Int) {
        selectedPos = real
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v.findViewById(R.id.card)
        val name: TextView = v.findViewById(R.id.tv_name)
        val meta: TextView = v.findViewById(R.id.tv_meta)
        val status: TextView = v.findViewById(R.id.tv_status)
        val action: MaterialButton = v.findViewById(R.id.btn_action)
    }
}
