package com.example.kennys_dokidoki_wallpaper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections

class TagPromptAdapter(
    private var items: MutableList<TagListItem>,
    private val onTagClick: (String) -> Unit,
    private val onCategoryClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    // カテゴリごとのソート設定
    private val categorySortMap = mutableMapOf<String, Boolean>()

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TAG = 1
    }

    fun isAlphabetical(categoryName: String): Boolean {
        return categorySortMap[categoryName] ?: false
    }

    // ドラッグ＆ドロップのためのアイテム入れ替え処理
    fun moveItem(fromPos: Int, toPos: Int): Boolean {
        if (items[fromPos] is TagListItem.Header) return false
        
        // 移動先のカテゴリが名前順ソート中なら移動させない（順序が固定されているため）
        // カテゴリを跨ぐ場合は、移動先カテゴリのソート状態を確認する必要があるわ
        val item = items[fromPos]
        if (item is TagListItem.TagItem) {
            // 現在のカテゴリ名を特定
            var targetHeader: TagListItem.Header? = null
            for (i in toPos downTo 0) {
                if (items[i] is TagListItem.Header) {
                    targetHeader = items[i] as TagListItem.Header
                    break
                }
            }
            if (targetHeader != null && isAlphabetical(targetHeader.name)) {
                return false // 名前順ソート中は手動移動禁止
            }
        }

        val removedItem = items.removeAt(fromPos)
        items.add(toPos, removedItem)
        notifyItemMoved(fromPos, toPos)
        return true
    }

    fun syncTagsToManager(context: android.content.Context) {
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
        
        val oldCategories = TagManager.categories.map { it.name }.toMutableList()
        TagManager.categories.clear()
        
        val createdNames = mutableSetOf<String>()
        for (nc in newCategories) {
            TagManager.categories.add(nc)
            createdNames.add(nc.name)
        }
        
        for (oldName in oldCategories) {
            if (!createdNames.contains(oldName)) {
                TagManager.categories.add(TagCategory(oldName))
            }
        }

        TagManager.saveTags(context)
        refreshItemsFromManager()
    }

    fun refreshItemsFromManager() {
        val newItems = mutableListOf<TagListItem>()
        TagManager.categories.forEachIndexed { cIndex, category ->
            newItems.add(TagListItem.Header(cIndex, category.name))
            
            val displayTags = if (isAlphabetical(category.name)) {
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

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is TagListItem.Header -> TYPE_HEADER
            is TagListItem.TagItem -> TYPE_TAG
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_tag_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_tag_prompt, parent, false)
            TagViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is TagListItem.Header) {
            holder.categoryName.text = item.name
            
            val isAlpha = isAlphabetical(item.name)
            if (isAlpha) {
                holder.btnSortMode.setImageResource(android.R.drawable.ic_menu_sort_alphabetically)
                holder.btnSortMode.alpha = 1.0f
            } else {
                holder.btnSortMode.setImageResource(android.R.drawable.ic_menu_sort_by_size)
                holder.btnSortMode.alpha = 0.4f
            }

            holder.btnSortMode.setOnClickListener {
                categorySortMap[item.name] = !isAlpha
                refreshItemsFromManager()
            }

            holder.categoryName.setOnClickListener {
                onCategoryClick(item.name)
            }
        } else if (holder is TagViewHolder && item is TagListItem.TagItem) {
            holder.tagName.text = "#${item.name}"
            holder.tagName.setOnClickListener {
                onTagClick(item.name)
            }
        }
    }

    override fun getItemCount() = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val categoryName: TextView = view.findViewById(R.id.tv_category_name)
        val btnSortMode: ImageButton = view.findViewById(R.id.btn_sort_mode)
    }

    class TagViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tagName: TextView = view.findViewById(R.id.tv_tag_name)
    }
}
