package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView

class PresetAdapter(
    private var presets: List<Preset>,
    private val onPresetClick: (Preset) -> Unit,
    private val onPresetLongClick: (Preset) -> Unit,
    private val onAddNewClick: (String) -> Unit,
    private val onCategorySettingsClick: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PRESET = 1
        private const val TYPE_ADD_NEW = 2
    }

    private val items = mutableListOf<AdapterItem>()
    private var matchingPresetIds: Set<String> = emptySet()

    init {
        buildItems()
    }

    private sealed class AdapterItem {
        data class Header(val title: String) : AdapterItem()
        data class PresetItem(val preset: Preset) : AdapterItem()
        data class AddNew(val category: String) : AdapterItem()
    }

    private fun buildItems() {
        items.clear()
        val byId = presets.associateBy { it.id }
        PresetListPolicy.rows(
            categoryOrder = PresetManager.categoryOrder.toList(),
            presets = presets.map { it.id to it.category },
            collapsed = PresetManager.collapsedCategories.toSet()
        ).forEach { row ->
            when (row) {
                is PresetListPolicy.Row.Header -> items.add(AdapterItem.Header(row.title))
                is PresetListPolicy.Row.Card -> {
                    byId[row.id]?.let { items.add(AdapterItem.PresetItem(it)) }
                }
                is PresetListPolicy.Row.AddNew -> items.add(AdapterItem.AddNew(row.category))
            }
        }
    }

    class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.card_view)
        val tvName: TextView = view.findViewById(R.id.tv_preset_name)
        val tvDetails: TextView = view.findViewById(R.id.tv_preset_details)
        val ivThumbnail: ImageView = view.findViewById(R.id.iv_preset_thumbnail)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvArrow: TextView = view.findViewById(R.id.tv_header_arrow)
        val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
        val btnSettings: ImageButton = view.findViewById(R.id.btn_category_settings)
        val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
        val btnRandom: ImageButton = view.findViewById(R.id.btn_category_random)
    }

    class AddNewViewHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.PresetItem -> TYPE_PRESET
            is AdapterItem.AddNew -> TYPE_ADD_NEW
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card_header, parent, false)
                view.findViewById<View>(R.id.btn_category_random).visibility = View.GONE
                view.findViewById<View>(R.id.tv_category_selection_count).visibility = View.GONE
                HeaderViewHolder(view)
            }
            TYPE_ADD_NEW -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preset_add_new, parent, false)
                AddNewViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
                PresetViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is HeaderViewHolder -> {
                val header = item as AdapterItem.Header
                val isCollapsed = PresetManager.collapsedCategories.contains(header.title)
                holder.tvArrow.text = if (isCollapsed) "▶" else "▼"
                holder.tvTitle.text = header.title
                
                holder.itemView.setOnClickListener {
                    PresetManager.toggleCollapsed(holder.itemView.context, header.title)
                    buildItems()
                    notifyDataSetChanged()
                }

                holder.btnSettings.setOnClickListener {
                    onCategorySettingsClick(header.title)
                }

                holder.ivDragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag(holder)
                    }
                    false
                }
            }
            is PresetViewHolder -> {
                val presetItem = item as AdapterItem.PresetItem
                val preset = presetItem.preset
                holder.tvName.text = preset.name
                holder.tvDetails.text = "${preset.width}x${preset.height} | Steps:${preset.steps} | Cards:${preset.activePromptStates.size}"
                
                if (ImageStoragePolicy.canDisplayWithoutNetwork(preset.thumbnailUri)) {
                    Glide.with(holder.ivThumbnail)
                        .load(preset.thumbnailUri)
                        .diskCacheStrategy(ImageStoragePolicy.glideDiskCache(preset.thumbnailUri))
                        .centerCrop()
                        .into(holder.ivThumbnail)
                } else {
                    Glide.with(holder.ivThumbnail).clear(holder.ivThumbnail)
                    holder.ivThumbnail.setImageDrawable(null)
                }

                val matches = preset.id in matchingPresetIds
                val density = holder.itemView.resources.displayMetrics.density
                // 淡いラベンダーではなく、はっきり見える濃い紫で固定する。
                holder.card.strokeColor = if (matches) 0xFF6A0DAD.toInt() else 0xFF49454F.toInt()
                holder.card.strokeWidth = ((if (matches) 4f else 1f) * density).toInt()
                holder.card.cardElevation = (if (matches) 10f else 1f) * density
                holder.card.setCardBackgroundColor(
                    if (matches) 0xFF190A24.toInt() else 0xFF1C1B1F.toInt()
                )

                holder.itemView.setOnClickListener { onPresetClick(preset) }
                holder.itemView.setOnLongClickListener { 
                    onPresetLongClick(preset)
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

    /**
     * MainActivityで確定した一致IDを受け取る。bind中に可変なビルダー状態を
     * 再計算しないため、プリセット適用直後でも枠が消えない。
     */
    fun updateMatchingPresetIds(ids: Set<String>, forceRebind: Boolean = false) {
        val newIds = ids.toSet()
        if (forceRebind) {
            matchingPresetIds = newIds
            notifyDataSetChanged()
            return
        }
        val changedIds = (matchingPresetIds - newIds) + (newIds - matchingPresetIds)
        if (changedIds.isEmpty()) return
        matchingPresetIds = newIds
        items.forEachIndexed { index, item ->
            if (item is AdapterItem.PresetItem && item.preset.id in changedIds) {
                notifyItemChanged(index)
            }
        }
    }

    fun updateList(newList: List<Preset>) {
        presets = newList
        buildItems()
        notifyDataSetChanged()
    }

    fun notifyPresetChanged(presetId: String) {
        val position = items.indexOfFirst { it is AdapterItem.PresetItem && it.preset.id == presetId }
        if (position >= 0) notifyItemChanged(position)
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
        
        val fromIdx = PresetManager.categoryOrder.indexOf(fromItem.title)
        val toIdx = PresetManager.categoryOrder.indexOf(toItem.title)
        
        if (fromIdx != -1 && toIdx != -1) {
            val cat = PresetManager.categoryOrder.removeAt(fromIdx)
            PresetManager.categoryOrder.add(toIdx, cat)
            buildItems()
            notifyItemMoved(fromPos, toPos)
            return true
        }
        return false
    }
}
