package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView

// Horizontal picker for Civitai showcase images.
class CivitaiShowcaseAdapter(
    private val onPick: (CivitaiApi.ShowImage) -> Unit
) : RecyclerView.Adapter<CivitaiShowcaseAdapter.Holder>() {

    private val items = mutableListOf<CivitaiApi.ShowImage>()
    var selectedUrl: String? = null
        private set

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_showcase)
        val thumb: ImageView = view.findViewById(R.id.iv_showcase)
        val badge: MaterialTextView = view.findViewById(R.id.tv_has_prompt)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_civitai_showcase, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val image = items[position]
        Glide.with(holder.thumb.context)
            .load(image.url)
            .override(300, 420)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .into(holder.thumb)
        holder.badge.visibility = if (image.prompt.isNotBlank()) View.VISIBLE else View.GONE

        val selected = image.url == selectedUrl && image.url.isNotEmpty()
        val colors = holder.card.context
        holder.card.strokeWidth = if (selected) {
            (4 * colors.resources.displayMetrics.density).toInt()
        } else {
            (1 * colors.resources.displayMetrics.density).toInt()
        }
        holder.card.strokeColor = MaterialColors.getColor(
            colors,
            if (selected) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant,
            0
        )
        holder.itemView.setOnClickListener {
            selectedUrl = image.url
            notifyDataSetChanged()
            onPick(image)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.thumb.context).clear(holder.thumb)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    fun submit(images: List<CivitaiApi.ShowImage>, preselect: CivitaiApi.ShowImage? = null) {
        items.clear()
        items.addAll(images)
        selectedUrl = preselect?.url
        notifyDataSetChanged()
    }
}
