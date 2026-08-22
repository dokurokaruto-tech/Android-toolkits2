package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * ビルダー画面下部の「選択中カード」横スクロールストリップ。
 * 選択レベル(1=青/2=オレンジ/3=赤)に応じて枠色を分ける。
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
        val root: LinearLayout = view.findViewById(R.id.strip_card_root)
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

        // サムネイル
        if (card.thumbnailUri != null) {
            Glide.with(holder.thumb)
                .load(card.thumbnailUri)
                .override(140, 200)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.thumb)
        } else {
            holder.thumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // 選択レベルに応じた枠色
        val bgRes = when (level) {
            1 -> R.drawable.bg_card_selected            // 青
            2 -> R.drawable.bg_card_selected_emphasis1  // オレンジ
            3 -> R.drawable.bg_card_selected_emphasis2  // 赤
            else -> R.drawable.bg_card_selected
        }
        holder.root.setBackgroundResource(bgRes)

        holder.root.setOnClickListener { onTap(card) }
    }

    override fun getItemCount() = items.size
}
