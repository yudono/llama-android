package com.llamacpp.local

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ModelAdapter(
    private var selectedPos: Int,
    private val onSelect: (Int) -> Unit,
    private val onDownload: (Int) -> Unit,
    private val onCancel: (Int) -> Unit,
    private val onPick: (Int) -> Unit,
    private val onQuantChange: (Int, String) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ModelAdapter.VH>() {

    var shown: List<AiModel> = AppData.models
        private set

    fun filter(category: String) {
        shown = if (category == "Semua") AppData.models
                else AppData.models.filter { it.category == category }
        notifyDataSetChanged()
    }

    fun realPosition(shownPos: Int): Int = AppData.models.indexOf(shown[shownPos])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val m = shown[pos]
        val real = realPosition(pos)
        h.name.text = m.name
        h.meta.text = "${m.quant}  |  ${m.paramSize}  |  ${m.fileSize}  |  RAM ${m.ram}"
        h.card.setCardBackgroundColor(
            ContextCompat.getColor(h.itemView.context,
                if (real == selectedPos) R.color.card_selected else R.color.card)
        )

        // Dropdown quant (default Q8_0). Ganti -> unduh file quant tsb.
        val qa = ArrayAdapter(h.itemView.context, R.layout.item_quant, AiModel.QUANTS)
        qa.setDropDownViewResource(R.layout.item_quant_dropdown)
        h.quant.adapter = qa
        h.quant.onItemSelectedListener = null
        h.quant.setSelection(AiModel.QUANTS.indexOf(m.quant).coerceAtLeast(0), false)
        h.quant.isEnabled = !m.downloading
        h.quant.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, i: Int, id: Long) {
                val q = AiModel.QUANTS[i]
                if (q != m.quant) onQuantChange(real, q)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        when {
            m.downloaded -> {
                h.status.text = if (real == selectedPos) "Active" else "Ready to use"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context,
                    if (real == selectedPos) R.color.green else R.color.text_dim))
                h.action.visibility = if (real == selectedPos) View.GONE else View.VISIBLE
                h.action.text = "Use"
                h.action.setOnClickListener { onSelect(real) }
                h.pick.visibility = View.GONE
                h.delete.visibility = View.VISIBLE
                h.delete.setOnClickListener { onDelete(real) }
            }
            m.downloading -> {
                // downloadId > 0 = unduhan HF; -1 = salinan file lokal (pick).
                h.status.text = if (m.downloadId > 0) "Downloading ${m.progressLabel()}"
                                else "Copying ${m.progressLabel()}"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.yellow))
                h.action.visibility = View.VISIBLE
                h.action.text = "Cancel"
                h.action.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.red))
                h.action.setOnClickListener { onCancel(real) }
                h.pick.visibility = View.GONE
                h.delete.visibility = View.GONE
            }
            else -> {
                h.status.text = "Not downloaded"
                h.status.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_dim))
                h.action.visibility = View.VISIBLE
                h.action.text = "Get"
                h.action.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.accent))
                h.action.setOnClickListener { onDownload(real) }
                h.pick.visibility = View.VISIBLE
                h.pick.setOnClickListener { onPick(real) }
                h.delete.visibility = View.GONE
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
        val quant: Spinner = v.findViewById(R.id.sp_quant)
        val status: TextView = v.findViewById(R.id.tv_status)
        val action: MaterialButton = v.findViewById(R.id.btn_action)
        val pick: MaterialButton = v.findViewById(R.id.btn_pick)
        val delete: MaterialButton = v.findViewById(R.id.btn_delete)
    }
}
