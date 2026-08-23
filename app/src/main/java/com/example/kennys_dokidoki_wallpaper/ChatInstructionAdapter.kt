package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

class ChatInstructionAdapter(
    private val sections: List<ChatInstructionPolicy.Section>,
    private val onClick: (ChatInstructionPolicy.Section) -> Unit
) : RecyclerView.Adapter<ChatInstructionAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_instruction)
        val title: TextView = view.findViewById(R.id.tv_instruction_item_title)
        val sub: TextView = view.findViewById(R.id.tv_instruction_item_sub)
        val preview: TextView = view.findViewById(R.id.tv_instruction_item_preview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_instruction, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val section = sections[position]
        holder.title.text = section.title
        holder.sub.text = section.subtitle
        holder.preview.text = ChatInstructionPolicy.preview(section)
        val muted = section.empty
        val color = MaterialColors.getColor(
            holder.itemView,
            if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant
            else com.google.android.material.R.attr.colorOnSurface
        )
        holder.title.alpha = if (muted) 0.7f else 1f
        holder.preview.setTextColor(color)
        holder.card.setOnClickListener { onClick(section) }
    }

    override fun getItemCount(): Int = sections.size
}
