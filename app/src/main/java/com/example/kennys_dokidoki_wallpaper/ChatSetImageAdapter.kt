package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class ChatSetImageAdapter(
    private val images: List<ImageEntry>,
    private val currentUri: String?,
    private val onPick: (Int, ImageEntry) -> Unit
) : RecyclerView.Adapter<ChatSetImageAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.image_thumb)
        val currentBorder: View = view.findViewById(R.id.current_border)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_set_image, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = images[position]
        Glide.with(holder.thumb.context)
            .load(entry.uri)
            .override(360, 360)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.thumb)

        val isCurrent = currentUri != null && entry.uri.toString() == currentUri
        holder.currentBorder.visibility = if (isCurrent) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (!ChatSetImagePicker.canSelect(images.size, pos)) return@setOnClickListener
            onPick(pos, images[pos])
        }
    }

    override fun getItemCount(): Int = images.size
}
