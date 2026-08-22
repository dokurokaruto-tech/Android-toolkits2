package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * ビルダー画面下部の「選択中カード」横スクロールストリップ。
 * 選択レベル(1=青/2=オレンジ/3=赤)に応じて枠色を変える。
 * タップすると選択解除（レベル0にする）。
 */
class SelectedCardStripAdapter(
    private val items: List<Pair<PromptCard, Int>>,
    private val onDeselect: (PromptCard) -> Unit
) : RecyclerView.Adapter<SelectedCardStripAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.strip_card_label)
        val border: LinearLayout = view.findViewById(R.id.strip_card_border)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_card_strip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (card, level) = items[position]
        holder.label.text = card.label
        val bgRes = when (level) {
            1 -> R.drawable.bg_card_selected
            2 -> R.drawable.bg_card_selected_emphasis1
            else -> R.drawable.bg_card_selected_emphasis2
        }
        holder.border.setBackgroundResource(bgRes)
        holder.itemView.setOnClickListener { onDeselect(card) }
    }

    override fun getItemCount(): Int = items.size
}
