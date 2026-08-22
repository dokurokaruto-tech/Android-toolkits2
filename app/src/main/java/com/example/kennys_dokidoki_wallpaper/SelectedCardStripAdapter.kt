package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
 * ビルダー画面下部の「選択中カード」横スクロールストリップ。
 * Material Design 3 の MaterialCardView を使用し、選択レベル
 * (1=青/2=オレンジ/3=赤) に応じてストローク色を分ける。
 * タップで選択を解除する。
 */
class SelectedCardStripAdapter(
    private val context: Context,
    private val onTap: (PromptCard) -> Unit
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
        val (cardData, level) = items[position]
        holder.label.text = cardData.label

        // サムネイル
        if (cardData.thumbnailUri != null) {
            Glide.with(holder.thumb)
                .load(cardData.thumbnailUri)
                .override(140, 200)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.thumb)
        } else {
            holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // 選択レベルに応じたストローク色（保護された3色）
        val density = holder.itemView.context.resources.displayMetrics.density
        val (color, width) = when (level) {
            1 -> Color.parseColor("#00F0FF") to (3 * density).toInt()  // 青
            2 -> Color.parseColor("#FF9800") to (3 * density).toInt()  // オレンジ
            3 -> Color.parseColor("#E91E63") to (3 * density).toInt()  // 赤
            else -> Color.parseColor("#49454F") to (1 * density).toInt()
        }
        holder.card.strokeColor = ColorStateList.valueOf(color)
        holder.card.strokeWidth = width

        holder.card.setOnClickListener { onTap(cardData) }
    }

    override fun getItemCount() = items.size
}
