package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ApiKeyOrderAdapter(
    private val entries: MutableList<OpenRouterManager.ApiKeyEntry>,
    private val context: android.content.Context,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onItemClick: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ApiKeyOrderAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val handle: ImageView = view.findViewById(R.id.iv_handle)
        val tvKeyInfo: TextView = view.findViewById(R.id.tv_key_info)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete_key)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_api_key_order, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        val count = OpenRouterManager.getUsageCount(context, entry.key)
        
        holder.tvKeyInfo.text = "${position + 1}. ${entry.label} ($count/50)"
        
        // ハンバーガーアイコンを触った瞬間にドラッグを開始するわよ！
        holder.handle.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
            }
            false
        }

        holder.tvKeyInfo.setOnClickListener {
            onItemClick(holder.adapterPosition)
        }
        
        holder.btnDelete.setOnClickListener {
            onDelete(holder.adapterPosition)
        }
    }

    override fun getItemCount() = entries.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val movedItem = entries.removeAt(fromPosition)
        entries.add(toPosition, movedItem)
        notifyItemMoved(fromPosition, toPosition)
        notifyItemRangeChanged(0, entries.size)
    }
}
