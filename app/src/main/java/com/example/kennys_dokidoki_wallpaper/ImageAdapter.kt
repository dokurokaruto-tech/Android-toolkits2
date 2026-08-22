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
import kotlin.math.max
import kotlin.math.min

class ImageAdapter(
    private val images: List<ImageEntry>,
    private val onImageClick: (position: Int, entry: ImageEntry, view: View) -> Unit,
    private val onDeleteClick: (ImageEntry, Int) -> Unit,
    private val onStartWallpaperClick: (ImageEntry) -> Unit,
    private val onEditTagsClick: (ImageEntry) -> Unit,
    private val onSelectionModeChanged: (Boolean) -> Unit,
    private val onSelectionCountChanged: (Int) -> Unit
) : RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    var isGeneratedViewerMode: Boolean = false
    var activeImageUri: String? = null
    var isSelectionMode = false
        private set

    val selectedPositions = mutableSetOf<Int>()
    private var lastLongPressedPosition = -1

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
        val selectionCheck: ImageView = view.findViewById(R.id.selection_check)
    }

    fun startSelectionMode(position: Int) {
        isSelectionMode = true
        selectedPositions.add(position)
        lastLongPressedPosition = position
        onSelectionModeChanged(true)
        onSelectionCountChanged(selectedPositions.size)
        notifyDataSetChanged()
    }

    fun stopSelectionMode() {
        isSelectionMode = false
        selectedPositions.clear()
        lastLongPressedPosition = -1
        onSelectionModeChanged(false)
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedPositions.clear()
        selectedPositions.addAll(images.indices)
        onSelectionCountChanged(selectedPositions.size)
        notifyDataSetChanged()
    }

    fun getSelectedEntries(): List<ImageEntry> {
        return selectedPositions.map { images[it] }
    }

    fun getActiveImageIndex(): Int? {
        val uri = activeImageUri ?: return null
        val index = images.indexOfFirst { it.uri.toString() == uri }
        return if (index != -1) index else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val entry = images[position]
        val context = holder.itemView.context
        
        // PC生成画像の一覧ではサーバー側で圧縮したモバイル用サムネイルを使う。
        // タップ後の全画面だけ entry.uri（オリジナル）を読む。
        val gridImageUri = entry.thumbnailUri ?: entry.uri
        Glide.with(holder.imageView.context)
            .load(gridImageUri)
            .override(400, 711)
            .diskCacheStrategy(ImageStoragePolicy.glideDiskCache(gridImageUri))
            .centerCrop()
            .into(holder.imageView)
            
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

        // 複数選択モードの描画
        if (isSelectionMode) {
            holder.btnMore.visibility = View.GONE
            if (selectedPositions.contains(position)) {
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
            if (isSelectionMode) return@setOnClickListener
            val popup = PopupMenu(view.context, view)
            popup.menu.add("画像属性（タグ）の編集")
            if (isGeneratedViewerMode) {
                popup.menu.add("削除")
            } else {
                popup.menu.add("壁紙をスタート")
                if (entry.cropRect != null || entry.croppedUri != null) {
                    popup.menu.add("クロップデータを削除")
                }
                popup.menu.add("ソフトウェアから削除")
            }

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "壁紙をスタート" -> onStartWallpaperClick(entry)
                    "画像属性（タグ）の編集" -> onEditTagsClick(entry)
                    "クロップデータを削除" -> {
                        entry.cropRect = null
                        entry.croppedUri = null
                        DataManager.saveData(view.context)
                        notifyItemChanged(position)
                    }
                    "削除", "ソフトウェアから削除" -> onDeleteClick(entry, position)
                }
                true
            }
            popup.show()
        }

        // アイコンをタップしてアクティブ/非アクティブを切り替え
        holder.checkActive.setOnClickListener {
            if (isSelectionMode) return@setOnClickListener
            entry.isActive = !entry.isActive
            DataManager.saveData(it.context)
            notifyItemChanged(position)
        }
            
        holder.itemView.setOnClickListener {
            if (isSelectionMode) {
                if (selectedPositions.contains(position)) {
                    selectedPositions.remove(position)
                } else {
                    selectedPositions.add(position)
                    lastLongPressedPosition = position
                }
                
                if (selectedPositions.isEmpty()) {
                    stopSelectionMode()
                } else {
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemChanged(position)
                }
            } else {
                // サムネイルタップ時はプレビュー（カルーセル）を呼ぶように、AlbumDetail側で設定したonImageClickを叩くわ！
                onImageClick(position, entry, holder.itemView)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (!isSelectionMode) {
                startSelectionMode(position)
            } else {
                if (lastLongPressedPosition != -1) {
                    val start = min(lastLongPressedPosition, position)
                    val end = max(lastLongPressedPosition, position)
                    for (i in start..end) {
                        selectedPositions.add(i)
                    }
                    lastLongPressedPosition = position
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemRangeChanged(start, end - start + 1)
                } else {
                    lastLongPressedPosition = position
                    selectedPositions.add(position)
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemChanged(position)
                }
            }
            true
        }
    }

    override fun getItemCount() = images.size
}
