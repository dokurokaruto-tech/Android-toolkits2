package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class ImageAdapter(
    private val images: List<ImageEntry>,
    private val onImageClick: (position: Int, entry: ImageEntry, view: View) -> Unit,
    private val onDeleteClick: (ImageEntry, Int) -> Unit,
    private val onStartWallpaperClick: (ImageEntry) -> Unit,
    private val onEditTagsClick: (ImageEntry) -> Unit,
    private val onCreatePresetClick: ((ImageEntry) -> Unit)? = null,
    private val onSelectionModeChanged: (Boolean) -> Unit,
    private val onSelectionCountChanged: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    var isGeneratedViewerMode: Boolean = false
    var activeImageUri: String? = null
    var isSelectionMode = false
        private set

    private val selection = ImageSelection()
    private var reportedSelectionCount = 0

    init {
        registerAdapterDataObserver(ImageSelectionObserver(::onImagesChanged))
    }
    private var savedGeneratedKeys: Set<String> = emptySet()

    fun setSavedGenerated(keys: Set<String>) {
        if (savedGeneratedKeys == keys) {
            return
        }
        savedGeneratedKeys = keys.toSet()
        notifyDataSetChanged()
    }

    class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.image_view)
        val tagsView: TextView = view.findViewById(R.id.tags_view)
        val tagGradient: View = view.findViewById(R.id.tag_gradient)
        val checkActive: ImageView = view.findViewById(R.id.check_active)
        val iconCropped: ImageView = view.findViewById(R.id.icon_cropped)
        val btnMore: ImageView = view.findViewById(R.id.btn_more)
        val highlightBorder: View = view.findViewById(R.id.highlight_border)
        val activeIndicator: View = view.findViewById(R.id.active_indicator)
        val tvActiveLabel: TextView = view.findViewById(R.id.tv_active_label)
        val selectionOverlay: View = view.findViewById(R.id.selection_overlay)
        val savedBorder: View = view.findViewById(R.id.saved_image_border)
        val savedLabel: TextView = view.findViewById(R.id.saved_image_label)
        val selectionCheck: ImageView = view.findViewById(R.id.selection_check)
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_with_tags, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val entry = images[position]
        val boundKey = ImageSelection.key(entry.uri.toString())
        val context = holder.itemView.context
        
        // PC生成画像の一覧ではサーバー側で圧縮したモバイル用サムネイルを使う。
        // 端末に保存済みならそれを載せ、無ければストリーミングしつつ裏で保存する。
        // タップ後の全画面だけ entry.uri（オリジナル）を読む。
        val remoteThumb = entry.thumbnailUri?.toString()
            ?.takeIf { ThumbnailLocalCachePolicy.isRemote(it) }
        val gridImageUri: Any = when {
            remoteThumb == null -> entry.thumbnailUri ?: entry.uri
            else -> ThumbnailLocalCache.existingLibrary(context, remoteThumb) ?: remoteThumb
        }
        if (remoteThumb != null) ThumbnailLocalCache.ensureLibrary(context, remoteThumb)
        val gridModelUri = Uri.parse(gridImageUri.toString())
        holder.imageView.loadThumb(
            gridImageUri,
            ImageMemoryPressurePolicy.GRID_THUMB_WIDTH,
            ImageMemoryPressurePolicy.GRID_THUMB_HEIGHT,
            ImageStoragePolicy.glideDiskCache(gridModelUri)
        )
        ImageMemoryGovernor.onGridBind(holder.imageView.context)
            
        // 生成画像閲覧でもケバブは出す。壁紙用のチェックだけ隠す。
        if (isGeneratedViewerMode) {
            holder.checkActive.visibility = View.GONE
            holder.iconCropped.visibility = View.GONE
        } else {
            holder.checkActive.visibility = View.VISIBLE
        }

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

        // 再生中のハイライト判定
        val isCurrentlyActive = activeImageUri != null && entry.uri.toString() == activeImageUri
        if (isCurrentlyActive && !isSelectionMode) {
            holder.highlightBorder.visibility = View.VISIBLE
            holder.activeIndicator.visibility = View.VISIBLE
            holder.tvActiveLabel.visibility = View.VISIBLE
        } else {
            holder.highlightBorder.visibility = View.GONE
            holder.activeIndicator.visibility = View.GONE
            holder.tvActiveLabel.visibility = View.GONE
        }

        // 選択状態と保存状態は別表示。リサイクル時にも必ず解除する。
        val saved = isGeneratedViewerMode &&
            GeneratedSavedPolicy.isSaved(entry.uri.toString(), savedGeneratedKeys)
        holder.savedBorder.visibility = if (saved && !isSelectionMode) View.VISIBLE else View.GONE
        holder.savedLabel.visibility = if (saved) View.VISIBLE else View.GONE

        // 複数選択モードの描画
        if (isSelectionMode) {
            holder.btnMore.visibility = View.GONE
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

        // アクティブ状態のアイコン切り替え
        if (entry.isActive) {
            holder.checkActive.setImageResource(R.drawable.ic_cyber_check)
            holder.imageView.alpha = 1.0f
        } else {
            holder.checkActive.setImageResource(R.drawable.ic_cyber_uncheck)
            holder.imageView.alpha = 0.5f
        }

        // クロップ済みアイコンの表示
        holder.iconCropped.visibility = if ((entry.cropRect != null || entry.croppedUri != null) && !isSelectionMode) View.VISIBLE else View.GONE

        // 3点メニューボタンの処理
        holder.btnMore.setOnClickListener { view ->
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION || isSelectionMode) {
                return@setOnClickListener
            }
            val popup = PopupMenu(view.context, view)
            popup.menu.add("画像属性（タグ）の編集")
            if (isGeneratedViewerMode) {
                popup.menu.add(PresetSavePolicy.FROM_IMAGE_MENU_LABEL)
                popup.menu.add("削除")
            } else {
                popup.menu.add("壁紙をスタート")
                if (entry.cropRect != null || entry.croppedUri != null) {
                    popup.menu.add("クロップデータを削除")
                }
                popup.menu.add("ソフトウェアから削除")
            }

            popup.setOnMenuItemClickListener { item ->
                val currentPos = positionOf(boundKey)
                val currentEntry = images.getOrNull(currentPos) ?: return@setOnMenuItemClickListener true
                when (item.title) {
                    "壁紙をスタート" -> onStartWallpaperClick(currentEntry)
                    "画像属性（タグ）の編集" -> onEditTagsClick(currentEntry)
                    "クロップデータを削除" -> {
                        currentEntry.cropRect = null
                        currentEntry.croppedUri = null
                        DataManager.saveData(view.context)
                        notifyItemChanged(currentPos)
                    }
                    "削除", "ソフトウェアから削除" -> onDeleteClick(currentEntry, currentPos)
                    PresetSavePolicy.FROM_IMAGE_MENU_LABEL -> onCreatePresetClick?.invoke(currentEntry)
                }
                true
            }
            popup.show()
        }

        // アイコンをタップしてアクティブ/非アクティブを切り替え
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
                onImageClick(currentPos, currentEntry, holder.itemView)
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

    override fun onViewRecycled(holder: ImageViewHolder) {
        Glide.with(holder.imageView.context).clear(holder.imageView)
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = images.size
}
