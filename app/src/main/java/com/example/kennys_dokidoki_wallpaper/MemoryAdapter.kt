package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MemoryAdapter(
    private val memories: List<String>,
    private val onEditClick: (Int, String) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMemoryText: TextView = view.findViewById(R.id.tv_memory_text)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_memory)
        val root: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val memory = memories[position]
        holder.tvMemoryText.text = memory
        
        holder.root.setOnClickListener {
            onEditClick(position, memory)
        }
        
        holder.btnDelete.setOnClickListener {
            onDeleteClick(position)
        }
    }

    override fun getItemCount() = memories.size
}
