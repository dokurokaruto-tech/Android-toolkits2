package com.example.kennys_dokidoki_wallpaper

import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class PromptCardAdapter(
    private var cards: List<PromptCard>,
    private val onSelectionChanged: () -> Unit,
    private val onLongClick: (PromptCard) -> Unit,
    private val onAddNewClick: (String) -> Unit,
    private val onCategorySettingsClick: (String) -> Unit,
    private val onRandomToggleClick: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CARD = 1
        private const val TYPE_ADD_NEW = 2
    }

    private val items = mutableListOf<AdapterItem>()
    private var randomizerEditingCategory: String? = null

    init {
        buildItems()
    }

    private sealed class AdapterItem {
        data class Header(val title: String) : AdapterItem()
        data class Card(val card: PromptCard) : AdapterItem()
        data class AddNew(val category: String) : AdapterItem()
    }

    private fun buildItems() {
        items.clear()
        val categorized = cards.groupBy { it.category }
        
        for (category in PromptCardManager.categoryOrder) {
            items.add(AdapterItem.Header(category))
            if (!PromptCardManager.collapsedCategories.contains(category)) {
                categorized[category]?.forEach { card ->
                    items.add(AdapterItem.Card(card))
                }
                items.add(AdapterItem.AddNew(category))
            }
        }
    }

    class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.iv_thumbnail)
        val tvLabel: TextView = view.findViewById(R.id.tv_label)
        val selectionOverlay: View = view.findViewById(R.id.selection_overlay)
        val ivRandomizerIndicator: ImageView = view.findViewById(R.id.iv_randomizer_indicator)
        val tvRandomProbability: TextView = view.findViewById(R.id.tv_random_probability)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvArrow: TextView = view.findViewById(R.id.tv_header_arrow)
        val tvSelectionCount: TextView = view.findViewById(R.id.tv_category_selection_count)
        val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
        val btnRandom: ImageButton = view.findViewById(R.id.btn_category_random)
        val btnRandomEdit: ImageButton = view.findViewById(R.id.btn_category_random_edit)
        val btnSettings: ImageButton = view.findViewById(R.id.btn_category_settings)
        val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
    }

    class AddNewViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.Card -> TYPE_CARD
            is AdapterItem.AddNew -> TYPE_ADD_NEW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_ADD_NEW -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card_add_new, parent, false)
                AddNewViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card, parent, false)
                CardViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> {
                val header = item as AdapterItem.Header
                val isCollapsed = PromptCardManager.collapsedCategories.contains(header.title)
                holder.tvArrow.text = if (isCollapsed) "▶" else "▼"
                holder.tvTitle.text = header.title

                // このカテゴリー内で現在選択されているカード枚数（0なら非表示）
                val selectedInCategory = PromptCardManager.promptCards.count {
                    it.category.trim() == header.title.trim() && PromptCardManager.selectionLevels.containsKey(it.id)
                }
                if (selectedInCategory > 0) {
                    holder.tvSelectionCount.visibility = View.VISIBLE
                    holder.tvSelectionCount.text = selectedInCategory.toString()
                    holder.tvSelectionCount.setTextColor(selectionCountColor(selectedInCategory))
                } else {
                    holder.tvSelectionCount.visibility = View.GONE
                }

                // Random Button state
                val isRandom = PromptCardManager.randomEnabledCategories.contains(header.title)
                holder.btnRandom.setColorFilter(if (isRandom) Color.parseColor("#00F0FF") else Color.parseColor("#8892B0"))
                holder.btnRandom.alpha = if (isRandom) 1.0f else 0.5f

                // Random Edit Button state
                val isEditing = randomizerEditingCategory == header.title
                holder.btnRandomEdit.setColorFilter(if (isEditing) Color.parseColor("#FFCC00") else Color.parseColor("#8892B0"))
                holder.btnRandomEdit.alpha = if (isEditing) 1.0f else 0.5f

                holder.itemView.setOnClickListener {
                    PromptCardManager.toggleCollapsed(holder.itemView.context, header.title)
                    buildItems()
                    notifyDataSetChanged()
                }

                holder.btnRandom.setOnClickListener {
                    onRandomToggleClick(header.title)
                    notifyItemChanged(position)
                }

                holder.btnRandomEdit.setOnClickListener {
                    if (randomizerEditingCategory == header.title) {
                        randomizerEditingCategory = null
                    } else {
                        randomizerEditingCategory = header.title
                    }
                    notifyDataSetChanged()
                }

                holder.btnSettings.setOnClickListener {
                    onCategorySettingsClick(header.title)
                }

                holder.ivDragHandle.setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(holder)
                    }
                    false
                }
            }
            is CardViewHolder -> {
                val cardItem = item as AdapterItem.Card
                val card = cardItem.card
                holder.tvLabel.text = card.label
                
                if (card.thumbnailUri != null) {
                    Glide.with(holder.ivThumbnail.context)
                        .load(card.thumbnailUri)
                        .into(holder.ivThumbnail)
                } else {
                    holder.ivThumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
                }

                // Randomizer indicator
                val isIncluded = PromptCardManager.randomizerIncludedIds.contains(card.id)
                holder.ivRandomizerIndicator.visibility = if (isIncluded || card.useIndividualRandomizer) View.VISIBLE else View.GONE
                
                // 個別ランダマイザーの確率表示
                if (card.useIndividualRandomizer) {
                    holder.tvRandomProbability.visibility = View.VISIBLE
                    holder.tvRandomProbability.text = "${card.randomizerProbability}%"
                } else {
                    holder.tvRandomProbability.visibility = View.GONE
                }

                val level = PromptCardManager.selectionLevels[card.id] ?: 0
                when (level) {
                    1 -> {
                        holder.selectionOverlay.visibility = View.VISIBLE
                        holder.selectionOverlay.setBackgroundResource(R.drawable.bg_card_selected)
                    }
                    2 -> {
                        holder.selectionOverlay.visibility = View.VISIBLE
                        holder.selectionOverlay.setBackgroundResource(R.drawable.bg_card_selected_emphasis1)
                    }
                    3 -> {
                        holder.selectionOverlay.visibility = View.VISIBLE
                        holder.selectionOverlay.setBackgroundResource(R.drawable.bg_card_selected_emphasis2)
                    }
                    else -> {
                        holder.selectionOverlay.visibility = View.GONE
                    }
                }
                
                holder.itemView.setOnClickListener {
                    if (randomizerEditingCategory == card.category) {
                        PromptCardManager.toggleRandomizerInclusion(holder.itemView.context, card.id)
                        notifyItemChanged(position)
                    } else {
                        val nextLevel = (level + 1) % 4
                        if (nextLevel == 0) {
                            PromptCardManager.selectionLevels.remove(card.id)
                        } else {
                            PromptCardManager.selectionLevels[card.id] = nextLevel
                        }
                        notifyItemChanged(position)
                        onSelectionChanged()
                    }
                }

                holder.itemView.setOnLongClickListener {
                    onLongClick(card)
                    true
                }
            }
            is AddNewViewHolder -> {
                val addNew = item as AdapterItem.AddNew
                holder.itemView.setOnClickListener {
                    onAddNewClick(addNew.category)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<PromptCard>) {
        cards = newList
        val allIds = newList.map { it.id }.toSet()
        val toRemove = PromptCardManager.selectionLevels.keys.filter { !allIds.contains(it) }
        toRemove.forEach { PromptCardManager.selectionLevels.remove(it) }
        buildItems()
        notifyDataSetChanged()
    }

    /**
     * Returns selected cards with their emphasis level (1, 2, or 3)
     */
    fun getSelectedCardsWithLevels(): List<Pair<PromptCard, Int>> {
        // カテゴリーの並び順に従ってソート（カテゴリー順が変われば追従）
        val catOrder = PromptCardManager.categoryOrder
        return cards
            .filter { PromptCardManager.selectionLevels.containsKey(it.id) }
            .sortedBy { catOrder.indexOf(it.category).let { i -> if (i >= 0) i else Int.MAX_VALUE } }
            .map { it to (PromptCardManager.selectionLevels[it.id] ?: 1) }
    }
    
    fun clearSelection() {
        PromptCardManager.selectionLevels.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun getSpanSize(position: Int, columns: Int): Int {
        return when (getItemViewType(position)) {
            TYPE_HEADER -> columns
            else -> 1
        }
    }

    fun moveCategory(fromPos: Int, toPos: Int): Boolean {
        val fromItem = items.getOrNull(fromPos) as? AdapterItem.Header ?: return false
        val toItem = items.getOrNull(toPos) as? AdapterItem.Header ?: return false
        
        val fromIdx = PromptCardManager.categoryOrder.indexOf(fromItem.title)
        val toIdx = PromptCardManager.categoryOrder.indexOf(toItem.title)
        
        if (fromIdx != -1 && toIdx != -1) {
            val cat = PromptCardManager.categoryOrder.removeAt(fromIdx)
            PromptCardManager.categoryOrder.add(toIdx, cat)
            buildItems()
            notifyItemMoved(fromPos, toPos)
            return true
        }
        return false
    }

    /**
     * 指定カードIDのアダプター上の位置を返す（見つからなければ -1）。
     * ストリップ長押しで該当カードへスクロールする際に使う。
     */
    fun findPositionOfCard(cardId: String): Int {
        for (i in items.indices) {
            val item = items[i]
            if (item is AdapterItem.Card && item.card.id == cardId) return i
        }
        return -1
    }

    /**
     * 選択状態が変わったとき、カテゴリーヘッダーの選択枚数表示だけを更新する。
     */
    fun notifyHeadersChanged() {
        for (i in items.indices) {
            if (items[i] is AdapterItem.Header) {
                notifyItemChanged(i)
            }
        }
    }

    /**
     * 選択枚数に応じた色を返す。
     * 1枚＝青、増えるごとに赤へ寄り、10枚(最大)で一番深い赤。10を超えても変わらない。
     * HSVで色相を青(210°)→赤(360°)へ線形補間し、彩度を高く保つことで中間色も鮮やかに。
     */
    private fun selectionCountColor(count: Int): Int {
        val n = count.coerceIn(1, 10)
        val t = (n - 1) / 9f
        val hue = 210f + 150f * t
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1.0f))
    }
}
