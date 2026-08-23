package com.example.kennys_dokidoki_wallpaper

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView

class BulkThumbnailAdapter(
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<BulkThumbnailAdapter.Holder>() {

    private val visible = mutableListOf<BulkThumbnailPickerPolicy.Entry>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_bulk_item)
        val thumb: ImageView = view.findViewById(R.id.iv_bulk_thumb)
        val label: MaterialTextView = view.findViewById(R.id.tv_bulk_label)
        val status: Chip = view.findViewById(R.id.chip_bulk_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bulk_thumbnail, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = visible[position]
        holder.label.text = entry.label
        holder.status.text = entry.status
        holder.card.isChecked = entry.selected
        paintStatus(holder, entry.hasThumbnail)
        paintSelection(holder.card, entry.selected)
        bindThumb(holder.thumb, entry.thumbnail)
        holder.itemView.setOnClickListener { onToggle(entry.id) }
    }

    override fun getItemCount(): Int = visible.size

    fun submit(items: List<BulkThumbnailPickerPolicy.Entry>) {
        visible.clear()
        visible.addAll(items)
        notifyDataSetChanged()
    }

    private fun bindThumb(view: ImageView, raw: String?) {
        if (BulkThumbnailPickerPolicy.hasThumbnail(raw)) {
            val uri = Uri.parse(raw)
            view.imageTintList = null
            Glide.with(view)
                .load(uri)
                .diskCacheStrategy(ImageStoragePolicy.glideDiskCache(uri))
                .into(view)
        } else {
            Glide.with(view).clear(view)
            view.setImageResource(android.R.drawable.ic_menu_gallery)
            view.imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#CAC4D0"))
        }
    }

    private fun paintStatus(holder: Holder, hasThumbnail: Boolean) {
        val bg = MaterialColors.getColor(
            holder.status,
            if (hasThumbnail) com.google.android.material.R.attr.colorSecondaryContainer
            else com.google.android.material.R.attr.colorSurfaceVariant
        )
        val fg = MaterialColors.getColor(
            holder.status,
            if (hasThumbnail) com.google.android.material.R.attr.colorOnSecondaryContainer
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        holder.status.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bg)
        holder.status.setTextColor(fg)
    }

    private fun paintSelection(card: MaterialCardView, selected: Boolean) {
        val density = card.resources.displayMetrics.density
        card.strokeWidth = ((if (selected) 2f else 1f) * density).toInt()
        card.strokeColor = MaterialColors.getColor(
            card,
            if (selected) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant
        )
    }
}
