package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class ChatInstructionAdapter(
    private val onCategory: (ChatInstructionPolicy.Category) -> Unit,
    private val onLeaf: (ChatInstructionPolicy.Leaf) -> Unit
) : RecyclerView.Adapter<ChatInstructionAdapter.Holder>() {

    private val rows = mutableListOf<ChatInstructionPolicy.Row>()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_instruction)
        val title: TextView = view.findViewById(R.id.tv_instruction_item_title)
        val sub: TextView = view.findViewById(R.id.tv_instruction_item_sub)
        val preview: TextView = view.findViewById(R.id.tv_instruction_item_preview)
    }

    fun submit(next: List<ChatInstructionPolicy.Row>) {
        rows.clear()
        rows.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChatInstructionPolicy.Row.Group -> 0
        is ChatInstructionPolicy.Row.Item -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_instruction, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val density = holder.itemView.resources.displayMetrics.density
        when (val row = rows[position]) {
            is ChatInstructionPolicy.Row.Group -> {
                holder.card.setContentPadding(0, 0, 0, 0)
                (holder.card.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = (8 * density).toInt()
                holder.title.text = row.category.title
                holder.sub.text = if (row.expanded) {
                    "${row.category.subtitle} · 開いている"
                } else {
                    row.category.subtitle
                }
                holder.preview.text = if (row.expanded) "中身を隠す" else "中身を見る"
                holder.title.alpha = 1f
                holder.preview.setTextColor(
                    MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                holder.card.setOnClickListener { onCategory(row.category) }
            }
            is ChatInstructionPolicy.Row.Item -> {
                (holder.card.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = (24 * density).toInt()
                holder.title.text = row.leaf.title
                holder.sub.text = row.leaf.subtitle
                holder.preview.text = ChatInstructionPolicy.preview(row.leaf)
                val muted = row.leaf.empty
                holder.title.alpha = if (muted) 0.7f else 1f
                holder.preview.setTextColor(
                    MaterialColors.getColor(
                        holder.itemView,
                        if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant
                        else com.google.android.material.R.attr.colorOnSurface
                    )
                )
                holder.card.setOnClickListener { onLeaf(row.leaf) }
            }
        }
    }

    override fun getItemCount(): Int = rows.size
}
