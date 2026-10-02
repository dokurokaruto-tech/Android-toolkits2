package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class AllImagesAdapter(
    private var images: List<ImageEntry>, // 表示用のリストとして扱うわ
    private val onImageClick: (ImageEntry, Int) -> Unit,
    private val onDeleteClick: (ImageEntry, Int) -> Unit,
    private val onEditTagsClick: (ImageEntry) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit,
    private val onSelectionCountChanged: (Int) -> Unit
) : RecyclerView.Adapter<AllImagesAdapter.ViewHolder>() {

    var isSelectionMode = false
        private set

    var activeImageUri: String? = null
    var homeImageUri: String? = null
    var chatImageUri: String? = null

    private val selection = ImageSelection()
    private var reportedSelectionCount = 0

    init {
        registerAdapterDataObserver(ImageSelectionObserver(::onImagesChanged))
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.image_view)
        val tagsView: TextView = view.findViewById(R.id.tags_view)
        val tagGradient: View = view.findViewById(R.id.tag_gradient)
        val highlightBorder: View = view.findViewById(R.id.highlight_border)
        val activeIndicator: View = view.findViewById(R.id.active_indicator)
        val tvActiveLabel: TextView = view.findViewById(R.id.tv_active_label)
        val btnMore: ImageView = view.findViewById(R.id.btn_more)
        val selectionOverlay: View = view.findViewById(R.id.selection_overlay)
        val selectionCheck: ImageView = view.findViewById(R.id.selection_check)
        val checkActive: ImageView = view.findViewById(R.id.check_active)
        val iconCropped: ImageView = view.findViewById(R.id.icon_cropped)
    }

    // フィルタリングなどでリストを差し替える時に使うわ
    fun updateList(newList: List<ImageEntry>) {
        images = newList
        notifyDataSetChanged()
    }

    private fun imageKeys(): List<String> = images.map { ImageSelection.key(it.uri.toString()) }

    private fun positionOf(key: String): Int = images.indexOfFirst {
        ImageSelection.key(it.uri.toString()) == key
    }

    private fun onImagesChanged() {
        val wasSelecting = isSelectionMode
        syncSelection()
        if (wasSelecting != isSelectionMode) {
            // 部分更新で最後の選択が消えた場合も、全行の操作表示を戻す。
            notifyDataSetChanged()
        }
    }

    private fun syncSelection() {
        selection.reconcile(imageKeys())
        val active = selection.size > 0
        if (active != isSelectionMode) {
            isSelectionMode = active
            onSelectionModeChanged(active)
        }
        if (reportedSelectionCount != selection.size) {
            reportedSelectionCount = selection.size
            onSelectionCountChanged(selection.size)
        }
    }

    fun startSelectionMode(position: Int) {
        selection.start(imageKeys(), position)
        syncSelection()
        notifyDataSetChanged()
    }

    fun stopSelectionMode() {
        selection.clear()
        syncSelection()
        notifyDataSetChanged()
    }

    fun selectAll() {
        selection.all(imageKeys())
        syncSelection()
        notifyDataSetChanged()
    }

    fun getSelectedEntries(): List<ImageEntry> {
        return selection.indices(imageKeys()).map { images[it] }
            .distinctBy { ImageSelection.key(it.uri.toString()) }
    }

    fun getActiveImageIndex(): Int? {
        val uri = activeImageUri ?: return null
        val index = images.indexOfFirst { it.uri.toString() == uri }
        return if (index != -1) index else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_with_tags, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = images[position]
        val boundKey = ImageSelection.key(entry.uri.toString())
        val context = holder.itemView.context
        
        val gridUri = entry.thumbnailUri ?: entry.uri
        holder.imageView.loadThumb(
            gridUri,
            ImageMemoryPressurePolicy.GRID_THUMB_WIDTH,
            ImageMemoryPressurePolicy.GRID_THUMB_HEIGHT,
            ImageStoragePolicy.glideDiskCache(gridUri, DiskCacheStrategy.RESOURCE)
        )
        ImageMemoryGovernor.onGridBind(holder.imageView.context)
        
        // タグの表示設定
        val settingsPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val showTags = settingsPrefs.getBoolean("show_tags_on_thumbnail", true)
        
        if (showTags) {
            // 自動付与されるタグも含めて表示するわよ！
            val effectiveTags = TagManager.getEffectiveTags(entry.tags)
            if (effectiveTags.isNotEmpty()) {
                holder.tagsView.visibility = View.VISIBLE
                holder.tagGradient.visibility = View.VISIBLE
                holder.tagsView.text = effectiveTags.joinToString(", ")
            } else {
                holder.tagsView.visibility = View.GONE
                holder.tagGradient.visibility = View.GONE
            }
        } else {
            holder.tagsView.visibility = View.GONE
            holder.tagGradient.visibility = View.GONE
        }
        
        val uriStr = entry.uri.toString()
        val isHome = homeImageUri != null && uriStr == homeImageUri
        val isChat = chatImageUri != null && uriStr == chatImageUri
        val isCurrentlyActive = isHome || isChat || (activeImageUri != null && uriStr == activeImageUri)
        if (isCurrentlyActive && !isSelectionMode) {
            holder.highlightBorder.visibility = View.VISIBLE
            holder.activeIndicator.visibility = View.GONE
            holder.tvActiveLabel.visibility = View.VISIBLE
            holder.tvActiveLabel.text = when {
                isHome && isChat -> "ホーム・チャット"
                isHome -> "ホーム"
                isChat -> "チャット"
                else -> "再生中"
            }
        } else {
            holder.highlightBorder.visibility = View.GONE
            holder.activeIndicator.visibility = View.GONE
            holder.tvActiveLabel.visibility = View.GONE
        }

        // 複数選択モードの描画
        if (isSelectionMode) {
            holder.btnMore.visibility = View.GONE // メニューは隠す
            if (selection.contains(boundKey)) {
                holder.selectionOverlay.visibility = View.VISIBLE
                holder.selectionCheck.visibility = View.VISIBLE
            } else {
                holder.selectionOverlay.visibility = View.GONE
                holder.selectionCheck.visibility = View.GONE
            }
        } else {
            holder.btnMore.visibility = View.VISIBLE
            holder.selectionOverlay.visibility = View.GONE
            holder.selectionCheck.visibility = View.GONE
        }

        // アクティブ状態のアイコン切り替え（通常モードのみ）
        if (entry.isActive) {
            holder.checkActive.setImageResource(R.drawable.ic_cyber_check)
            holder.imageView.alpha = 1.0f
        } else {
            holder.checkActive.setImageResource(R.drawable.ic_cyber_uncheck)
            holder.imageView.alpha = 0.5f
        }

        // クロップ済みアイコンの表示（新しい cropRect 方式に対応）
        holder.iconCropped.visibility = if ((entry.cropRect != null || entry.croppedUri != null) && !isSelectionMode) View.VISIBLE else View.GONE

        // 3点メニューボタンの処理
        holder.btnMore.setOnClickListener { view ->
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION || isSelectionMode) {
                return@setOnClickListener
            }
            
            val popup = PopupMenu(view.context, view)
            popup.menu.add("画像属性の編集")
            if (entry.cropRect != null || entry.croppedUri != null) {
                popup.menu.add("クロップデータを削除")
            }
            popup.menu.add("ソフトウェアから削除")
            
            popup.setOnMenuItemClickListener { item ->
                val currentPos = positionOf(boundKey)
                val currentEntry = images.getOrNull(currentPos) ?: return@setOnMenuItemClickListener true
                when (item.title) {
                    "画像属性の編集" -> onEditTagsClick(currentEntry)
                    "クロップデータを削除" -> {
                        currentEntry.cropRect = null
                        currentEntry.croppedUri = null
                        DataManager.saveData(view.context)
                        // 壁紙サービスにも通知を送るわよ！
                        view.context.sendBroadcast(android.content.Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED").apply {
                            setPackage(view.context.packageName)
                        })
                        notifyItemChanged(currentPos)
                    }
                    "ソフトウェアから削除" -> onDeleteClick(currentEntry, currentPos)
                }
                true
            }
            popup.show()
        }

        holder.checkActive.setOnClickListener {
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION || isSelectionMode) {
                return@setOnClickListener
            }
            val currentPos = positionOf(boundKey)
            val currentEntry = images.getOrNull(currentPos) ?: return@setOnClickListener
            currentEntry.isActive = !currentEntry.isActive
            DataManager.saveData(it.context)
            notifyItemChanged(currentPos)
        }

        holder.itemView.setOnClickListener {
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                return@setOnClickListener
            }
            val currentPos = positionOf(boundKey)
            val currentEntry = images.getOrNull(currentPos) ?: return@setOnClickListener
            if (isSelectionMode) {
                selection.toggle(imageKeys(), currentPos)
                syncSelection()
                notifyDataSetChanged()
            } else {
                onImageClick(currentEntry, currentPos)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                return@setOnLongClickListener true
            }
            val currentPos = positionOf(boundKey)
            if (currentPos < 0) {
                return@setOnLongClickListener true
            }
            if (!isSelectionMode) {
                startSelectionMode(currentPos)
            } else {
                selection.range(imageKeys(), currentPos)
                syncSelection()
                notifyDataSetChanged()
            }
            true
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        Glide.with(holder.imageView.context).clear(holder.imageView)
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = images.size
}
