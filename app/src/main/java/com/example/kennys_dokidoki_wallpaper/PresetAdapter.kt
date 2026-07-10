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

class PresetAdapter(
    private var presets: List<Preset>,
    private val onPresetClick: (Preset) -> Unit,
    private val onPresetLongClick: (Preset) -> Unit,
    private val onCategorySettingsClick: (String) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PRESET = 1
    }

    private val items = mutableListOf<AdapterItem>()

    init {
        buildItems()
    }

    private sealed class AdapterItem {
        data class Header(val title: String) : AdapterItem()
        data class PresetItem(val preset: Preset) : AdapterItem()
    }

    private fun buildItems() {
        items.clear()
        val categorized = presets.groupBy { it.category }
        
        for (category in PresetManager.categoryOrder) {
            items.add(AdapterItem.Header(category))
            if (!PresetManager.collapsedCategories.contains(category)) {
                categorized[category]?.forEach { preset ->
                    items.add(AdapterItem.PresetItem(preset))
                }
            }
        }
    }

    class PresetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_preset_name)
        val tvDetails: TextView = view.findViewById(R.id.tv_preset_details)
        val ivThumbnail: ImageView = view.findViewById(R.id.iv_preset_thumbnail)
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_header_title)
        val btnSettings: ImageButton = view.findViewById(R.id.btn_category_settings)
        val ivDragHandle: ImageView = view.findViewById(R.id.iv_drag_handle)
        val btnRandom: ImageButton = view.findViewById(R.id.btn_category_random)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AdapterItem.Header -> TYPE_HEADER
            is AdapterItem.PresetItem -> TYPE_PRESET
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_card_header, parent, false)
                view.findViewById<View>(R.id.btn_category_random).visibility = View.GONE
                HeaderViewHolder(view)
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
                holder.tvTitle.text = if (isCollapsed) "▶ ${header.title}" else "▼ ${header.title}"
                
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
                
                if (preset.thumbnailUri != null) {
                    holder.ivThumbnail.setImageURI(preset.thumbnailUri)
                } else {
                    holder.ivThumbnail.setImageDrawable(null)
                }

                holder.itemView.setOnClickListener { onPresetClick(preset) }
                holder.itemView.setOnLongClickListener { 
                    onPresetLongClick(preset)
                    true
                }
            }
        }
    }

    override fun getItemCount() = items.size

    fun updateList(newList: List<Preset>) {
        presets = newList
        buildItems()
        notifyDataSetChanged()
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
