package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TagCardAdapter(
    private var tags: List<String>,
    private val onTagClick: (String) -> Unit
) : RecyclerView.Adapter<TagCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTagName: TextView = view.findViewById(R.id.tv_tag_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag_prompt, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tag = tags[position]
        holder.tvTagName.text = "#$tag"
        holder.itemView.setOnClickListener { onTagClick(tag) }
    }

    override fun getItemCount() = tags.size

    fun updateTags(newTags: List<String>) {
        tags = newTags
        notifyDataSetChanged()
    }
}
