package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * Material Design 3 調のモデル選択リストの1件分。
 * 選択中は secondaryContainer で塗りつぶし、primary で縁取りする（MD3 の selected card）。
 * 左端の星でOpenRouterモデルをお気に入り登録できる（onToggleFavorite 未指定なら星は出ない）。
 */
data class ModelMd3Item(
    val id: String,
    val name: String,
    val contextLength: Int,
    val isFree: Boolean,
    val pricePerMillion: Double,
    val outputPricePerMillion: Double = 0.0
)

class ModelMd3Adapter(
    private val items: List<ModelMd3Item>,
    private val selectedId: String?,
    private val onToggleFavorite: ((ModelMd3Item) -> Unit)? = null,
    private val onSelect: (ModelMd3Item) -> Unit
) : RecyclerView.Adapter<ModelMd3Adapter.VH>() {

    var favorites: Set<String> = emptySet()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_model)
        val star: ImageView = view.findViewById(R.id.btn_favorite_star)
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
        holder.meta.text = metaText(item)
        bindStar(holder, item)

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

    /** お気に入りSetを差し替えて再描画する。スクロール位置を壊さないためAdapterは作り直さない。 */
    fun updateFavorites(next: Set<String>) {
        favorites = next
        notifyDataSetChanged()
    }

    private fun bindStar(holder: VH, item: ModelMd3Item) {
        val toggle = onToggleFavorite ?: run {
            holder.star.visibility = View.GONE
            holder.star.setOnClickListener(null)
            return
        }
        val ctx = holder.itemView.context
        val isFav = item.id in favorites
        holder.star.visibility = View.VISIBLE
        holder.star.setImageResource(
            if (isFav) R.drawable.ic_md3_star_filled else R.drawable.ic_md3_star_border
        )
        holder.star.setColorFilter(
            if (isFav) STAR_ON_COLOR
            else MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        )
        holder.star.contentDescription = ctx.getString(
            if (isFav) R.string.model_unfavorite else R.string.model_favorite
        )
        holder.star.setOnClickListener { toggle(item) }
    }

    private fun metaText(item: ModelMd3Item): String {
        val priceStr = when {
            item.isFree -> "Free"
            item.pricePerMillion < 0.0 -> "API key"
            else -> "In ${perMillion(item.pricePerMillion)} • Out ${perMillion(item.outputPricePerMillion)}"
        }
        val ctxStr = if (item.contextLength >= 1000) {
            "${item.contextLength / 1000}k"
        } else {
            "${item.contextLength}"
        }
        return "Context $ctxStr  •  $priceStr"
    }

    private companion object {
        // お気に入り星を塗る黄色
        val STAR_ON_COLOR = 0xFFFFC107.toInt()

        fun perMillion(value: Double) = "$" + String.format("%.2f", value) + "/M"
    }
}
