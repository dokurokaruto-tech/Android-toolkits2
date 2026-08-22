package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView

/**
 * ビルダー画面下部の「選択中カード」横スクロールストリップ（MD3）。
 * 選択レベル(1=青/2=オレンジ/3=赤)でMaterialCardViewのstrokeColorを動的設定。
 * タップで選択解除、長押しで編集ダイアログを開く。
 */
class SelectedCardStripAdapter(
    private val context: Context,
    private val onTap: (PromptCard) -> Unit,
    private val onLongClick: (PromptCard) -> Unit
) : RecyclerView.Adapter<SelectedCardStripAdapter.VH>() {

    private var items: List<Pair<PromptCard, Int>> = emptyList()

    fun update(cards: List<Pair<PromptCard, Int>>) {
        items = cards
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.strip_card_root)
        val thumb: ImageView = view.findViewById(R.id.strip_card_thumbnail)
        val label: TextView = view.findViewById(R.id.strip_card_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_card_strip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (card, level) = items[position]
        holder.label.text = card.label

        if (card.thumbnailUri != null) {
            Glide.with(holder.thumb)
                .load(card.thumbnailUri)
                .override(144, 208)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.thumb)
        } else {
            holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // 選択レベルに応じて枠色を動的設定（保護された3色）
        val color = when (level) {
            1 -> 0xFF00F0FF.toInt() // 青
            2 -> 0xFFFF9800.toInt() // オレンジ
            3 -> 0xFFE91E63.toInt() // 赤
            else -> 0xFF00F0FF.toInt()
        }
        holder.card.strokeColor = color

        holder.card.setOnClickListener { onTap(card) }
        holder.card.setOnLongClickListener {
            onLongClick(card)
            true
        }
    }

    override fun getItemCount() = items.size
}
