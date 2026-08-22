package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * Material Design 3 調のモデル選択リストの1件分。
 * 選択中は secondaryContainer で塗りつぶし、primary で縁取りする（MD3 の selected card）。
 */
data class ModelMd3Item(
    val id: String,
    val name: String,
    val contextLength: Int,
    val isFree: Boolean,
    val pricePerMillion: Double
)

class ModelMd3Adapter(
    private val items: List<ModelMd3Item>,
    private val selectedId: String?,
    private val onSelect: (ModelMd3Item) -> Unit
) : RecyclerView.Adapter<ModelMd3Adapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_model)
        val name: TextView = view.findViewById(R.id.tv_model_name)
        val meta: TextView = view.findViewById(R.id.tv_model_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_model_md3, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        val priceStr = when {
            item.isFree -> "Free"
            item.pricePerMillion < 0.0 -> "API key"
            else -> "$" + String.format("%.2f", item.pricePerMillion) + "/M"
        }
        val ctxStr = if (item.contextLength >= 1000) {
            "${item.contextLength / 1000}k"
        } else {
            "${item.contextLength}"
        }
        holder.meta.text = "Context $ctxStr  •  $priceStr"

        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        if (item.id == selectedId) {
            holder.card.setCardBackgroundColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, 0)
            )
            holder.card.setStrokeColor(
                android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimary, 0)
                )
            )
            holder.card.strokeWidth = (2 * density).toInt()
            holder.name.setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
            )
        } else {
            holder.card.setCardBackgroundColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSurface, 0)
            )
            holder.card.setStrokeColor(
                android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, 0)
                )
            )
            holder.card.strokeWidth = (1 * density).toInt()
            holder.name.setTextColor(
                MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0)
            )
        }

        holder.card.setOnClickListener { onSelect(item) }
    }

    override fun getItemCount(): Int = items.size
}
