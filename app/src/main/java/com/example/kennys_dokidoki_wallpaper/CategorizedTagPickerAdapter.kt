package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CategorizedTagPickerAdapter(
    private val selectedTags: MutableSet<String>,
    private val excludeCategory: String? = null,
    private val onTagToggled: (String, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: MutableList<TagListItem> = mutableListOf()
    
    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TAG = 1
        private const val PREFS_NAME = "tag_display_settings"
    }

    private fun isAlphabetical(context: Context, categoryName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("sort_alpha_$categoryName", false)
    }

    private fun setAlphabetical(context: Context, categoryName: String, isAlpha: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sort_alpha_$categoryName", isAlpha).apply()
    }

    fun isDraggable(position: Int): Boolean {
        if (position !in items.indices) return false
        val item = items[position]
        if (item is TagListItem.Header) return false
        
        var currentHeader: TagListItem.Header? = null
        for (i in position downTo 0) {
            if (items[i] is TagListItem.Header) {
                currentHeader = items[i] as TagListItem.Header
                break
            }
        }
        
        return currentHeader != null 
    }

    fun refreshItems(context: Context) {
        val newItems = mutableListOf<TagListItem>()
        TagManager.categories.forEachIndexed { cIndex, category ->
            // 除外指定されているカテゴリーはスキップするわ
            if (category.name == excludeCategory) return@forEachIndexed

            newItems.add(TagListItem.Header(cIndex, category.name))
            
            val isAlpha = isAlphabetical(context, category.name)
            val displayTags = if (isAlpha) {
                category.tags.sortedBy { it.lowercase() }
            } else {
                category.tags
            }

            displayTags.forEachIndexed { tIndex, tag ->
                newItems.add(TagListItem.TagItem(cIndex, tIndex, tag))
            }
        }
        this.items = newItems
        notifyDataSetChanged()
    }

    fun moveItem(fromPos: Int, toPos: Int, context: Context): Boolean {
        var sourceHeader: TagListItem.Header? = null
        for (i in fromPos downTo 0) {
            if (items[i] is TagListItem.Header) {
                sourceHeader = items[i] as TagListItem.Header
                break
            }
        }
        if (sourceHeader != null && isAlphabetical(context, sourceHeader.name)) return false

        var targetHeader: TagListItem.Header? = null
        for (i in toPos downTo 0) {
            if (items[i] is TagListItem.Header) {
                targetHeader = items[i] as TagListItem.Header
                break
            }
        }
        
        if (targetHeader != null && isAlphabetical(context, targetHeader.name)) {
            return false
        }

        val removedItem = items.removeAt(fromPos)
        items.add(toPos, removedItem)
        notifyItemMoved(fromPos, toPos)
        return true
    }

    fun syncTagsToManager(context: Context) {
        var currentCategoryIndex = -1
        val newCategories = mutableListOf<TagCategory>()
        
        for (item in items) {
            when (item) {
                is TagListItem.Header -> {
                    currentCategoryIndex++
                    newCategories.add(TagCategory(item.name))
                }
                is TagListItem.TagItem -> {
                    if (currentCategoryIndex != -1) {
                        newCategories[currentCategoryIndex].tags.add(item.name)
                    }
                }
            }
        }
        
        val oldCategoryOrder = TagManager.categories.map { it.name }
        
        // 除外されていたカテゴリーを保持するために、現在のマネージャーから取得するわ
        val excludedCategoryData = TagManager.categories.find { it.name == excludeCategory }

        TagManager.categories.clear()
        
        val createdNames = mutableSetOf<String>()
        for (nc in newCategories) {
            TagManager.categories.add(nc)
            createdNames.add(nc.name)
        }
        
        // 除外カテゴリーを元の位置に近い場所（または末尾）に戻すわ
        excludedCategoryData?.let {
            if (!createdNames.contains(it.name)) {
                TagManager.categories.add(it)
                createdNames.add(it.name)
            }
        }
        
        for (oldName in oldCategoryOrder) {
            if (!createdNames.contains(oldName)) {
                TagManager.categories.add(TagCategory(oldName))
            }
        }

        TagManager.saveTags(context)
        refreshItems(context)
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is TagListItem.Header) TYPE_HEADER else TYPE_TAG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_tag_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_tag_picker_chip, parent, false)
            TagViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        if (holder is HeaderViewHolder && item is TagListItem.Header) {
            holder.categoryName.text = item.name
            
            val isAlpha = isAlphabetical(context, item.name)
            if (isAlpha) {
                holder.btnSortMode.setImageResource(android.R.drawable.ic_menu_sort_alphabetically)
                holder.btnSortMode.alpha = 1.0f
            } else {
                holder.btnSortMode.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                holder.btnSortMode.alpha = 0.4f
            }

            holder.btnSortMode.setOnClickListener {
                setAlphabetical(context, item.name, !isAlpha)
                refreshItems(context)
            }

        } else if (holder is TagViewHolder && item is TagListItem.TagItem) {
            holder.textView.text = item.name
            
            val isSelected = selectedTags.contains(item.name)
            updateStyle(holder.textView, isSelected)
            
            holder.textView.setOnClickListener {
                val currentlySelected = selectedTags.contains(item.name)
                val newSelected = !currentlySelected
                if (newSelected) selectedTags.add(item.name) else selectedTags.remove(item.name)
                
                onTagToggled(item.name, newSelected)
                updateStyle(holder.textView, newSelected)
            }
        }
    }

    private fun updateStyle(textView: TextView, isSelected: Boolean) {
        val background = textView.background?.mutate() as? GradientDrawable ?: GradientDrawable().apply {
            cornerRadius = 12f * textView.context.resources.displayMetrics.density
            textView.background = this
        }

        if (isSelected) {
            background.setColor(Color.parseColor("#00F0FF"))
            background.setStroke(0, Color.TRANSPARENT)
            textView.setTextColor(Color.BLACK)
        } else {
            background.setColor(Color.parseColor("#1A2235"))
            background.setStroke((2 * textView.context.resources.displayMetrics.density).toInt(), Color.parseColor("#00F0FF"))
            textView.setTextColor(Color.WHITE)
        }
        
        textView.background = background
    }

    override fun getItemCount() = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val categoryName: TextView = view.findViewById(R.id.tv_category_name)
        val btnSortMode: ImageButton = view.findViewById(R.id.btn_sort_mode)
    }

    class TagViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.chip_tag)
    }
}
