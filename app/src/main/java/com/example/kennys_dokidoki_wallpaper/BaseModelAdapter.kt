package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

// Horizontal single-select strip mirroring the prompt card look.
class BaseModelAdapter(
    private val onSelect: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : RecyclerView.Adapter<BaseModelAdapter.Holder>() {

    private val items = mutableListOf<CheckpointInfo>()
    var activeName: String? = null
        private set

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.iv_thumbnail)
        val label: TextView = view.findViewById(R.id.tv_label)
        val overlay: View = view.findViewById(R.id.selection_overlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_base_model, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val model = items[position]
        holder.label.text = BaseModelManager.displayName(model.name)
        val localThumb = BaseModelManager.thumbnailFor(model.name)
        Glide.with(holder.thumb.context)
            .load(localThumb ?: GenerationAgentClient.checkpointPreviewUrl(holder.thumb.context, model.name))
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .placeholder(android.R.drawable.ic_menu_gallery)
            .error(android.R.drawable.ic_menu_gallery)
            .centerCrop()
            .into(holder.thumb)
        val selected = model.name == activeName
        holder.overlay.visibility = if (selected) View.VISIBLE else View.GONE
        if (selected) {
            holder.overlay.setBackgroundResource(R.drawable.bg_card_selected)
        }
        holder.itemView.setOnClickListener { onSelect(model.name) }
        holder.itemView.setOnLongClickListener { onLongClick(model.name); true }
    }

    override fun onViewRecycled(holder: Holder) {
        Glide.with(holder.thumb.context).clear(holder.thumb)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    fun submit(models: List<CheckpointInfo>, active: String?) {
        items.clear()
        items.addAll(models)
        activeName = active
        notifyDataSetChanged()
    }
}
