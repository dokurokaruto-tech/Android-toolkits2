package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class TagCardPickerAdapter(
    private var cards: List<PromptCard>,
    private val onCardClick: (PromptCard) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
    }

    private val items = mutableListOf<AdapterItem>()
    private val collapsedCategories = mutableSetOf<String>()

    init {
        // デフォルトですべてのカテゴリーを閉じた状態にするわ
        PromptCardManager.categoryOrder.forEach { collapsedCategories.add(it) }
        buildItems()
    }

    private sealed class AdapterItem {
        data class Header(val title: String) : AdapterItem()
        data class Card(val card: PromptCard) : AdapterItem()
    }

    private fun buildItems() {
        items.clear()
        val categorized = cards.groupBy { it.category }
        
        for (category in PromptCardManager.categoryOrder) {
            val categoryCards = categorized[category] ?: continue
            items.add(AdapterItem.Header(category))
            
            // カテゴリーが開いている場合のみカードを追加するわ
            if (!collapsedCategories.contains(category)) {
                categoryCards.forEach { card ->
                    items.add(AdapterItem.Card(card))
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.Card -> TYPE_CARD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card, parent, false)
            CardViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is AdapterItem.Header) {
            val isCollapsed = collapsedCategories.contains(item.title)
            holder.tvTitle.text = if (isCollapsed) "▶ ${item.title}" else "▼ ${item.title}"
            
            // 不要なボタンを非表示
            holder.btnRandom.visibility = View.GONE
            holder.btnRandomEdit.visibility = View.GONE
            holder.btnSettings.visibility = View.GONE
            holder.ivDragHandle.visibility = View.GONE

            // ヘッダー全体をクリック可能にして開閉を切り替えるわよ！
            holder.itemView.setOnClickListener {
                if (isCollapsed) {
                    collapsedCategories.remove(item.title)
                } else {
                    collapsedCategories.add(item.title)
                }
                buildItems()
                notifyDataSetChanged()
            }
        } else if (holder is CardViewHolder && item is AdapterItem.Card) {
            val card = item.card
            holder.tvLabel.text = card.label
            
            if (card.thumbnailUri != null) {
                Glide.with(holder.ivThumbnail.context)
                    .load(card.thumbnailUri)
                    .into(holder.ivThumbnail)
            } else {
                holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            // 選択済みのハイライト等はここでは不要（タップで即決定のため）
            holder.selectionOverlay.visibility = View.GONE
            holder.ivRandomizerIndicator.visibility = View.GONE
            holder.tvRandomProbability.visibility = View.GONE
            
            holder.itemView.setOnClickListener { onCardClick(card) }
        }
    }

    override fun getItemCount() = items.size

    fun getSpanSize(position: Int, columns: Int): Int {
        return if (getItemViewType(position) == TYPE_HEADER) columns else 1
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        val tvLabel: TextView = view.findViewById(R.id.tv_label)
        val selectionOverlay: View = view.findViewById(R.id.selection_overlay)
        val ivRandomizerIndicator: ImageView = view.findViewById(R.id.iv_randomizer_indicator)
        val tvRandomProbability: TextView = view.findViewById(R.id.tv_random_probability)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
        val btnRandom: View = view.findViewById(R.id.btn_category_random)
        val btnRandomEdit: View = view.findViewById(R.id.btn_category_random_edit)
        val btnSettings: View = view.findViewById(R.id.btn_category_settings)
        val ivDragHandle: View = view.findViewById(R.id.iv_drag_handle)
    }
}
