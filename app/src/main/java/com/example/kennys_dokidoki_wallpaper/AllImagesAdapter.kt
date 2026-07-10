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
import kotlin.math.max
import kotlin.math.min

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

    val selectedPositions = mutableSetOf<Int>()
    private var lastLongPressedPosition = -1

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
        selectedPositions.clear()
        lastLongPressedPosition = -1
        notifyDataSetChanged()
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
        return selectedPositions.mapNotNull { if (it in images.indices) images[it] else null }
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
        val context = holder.itemView.context
        
        // ★ アプリ内のサムネイルは「クロップを無視」してオリジナル画像を表示する
        Glide.with(holder.imageView.context)
            .load(entry.uri) // displayUri ではなくオリジナル (uri) を強制使用！
            .override(400, 711)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .centerCrop()
            .into(holder.imageView)
        
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
            holder.btnMore.visibility = View.GONE // メニューは隠す
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
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION || isSelectionMode) return@setOnClickListener
            
            val popup = PopupMenu(view.context, view)
            popup.menu.add("画像属性の編集")
            if (entry.cropRect != null || entry.croppedUri != null) {
                popup.menu.add("クロップデータを削除")
            }
            popup.menu.add("ソフトウェアから削除")
            
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "画像属性の編集" -> onEditTagsClick(entry)
                    "クロップデータを削除" -> {
                        entry.cropRect = null
                        entry.croppedUri = null
                        DataManager.saveData(view.context)
                        // 壁紙サービスにも通知を送るわよ！
                        view.context.sendBroadcast(android.content.Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED").apply {
                            setPackage(view.context.packageName)
                        })
                        notifyItemChanged(currentPos)
                    }
                    "ソフトウェアから削除" -> onDeleteClick(entry, currentPos)
                }
                true
            }
            popup.show()
        }

        holder.checkActive.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION || isSelectionMode) return@setOnClickListener
            
            entry.isActive = !entry.isActive
            DataManager.saveData(it.context)
            notifyItemChanged(currentPos)
        }
        
        holder.itemView.setOnClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener

            if (isSelectionMode) {
                if (selectedPositions.contains(currentPos)) {
                    selectedPositions.remove(currentPos)
                } else {
                    selectedPositions.add(currentPos)
                    lastLongPressedPosition = currentPos // トグルで最後に触ったものを更新
                }
                
                if (selectedPositions.isEmpty()) {
                    stopSelectionMode()
                } else {
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemChanged(currentPos)
                }
            } else {
                // コールバックを叩くわよ！
                onImageClick(entry, currentPos)
            }
        }

        // 長押し（複数選択モードのトリガー ＆ 範囲選択）
        holder.itemView.setOnLongClickListener {
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_POSITION) return@setOnLongClickListener true

            if (!isSelectionMode) {
                startSelectionMode(currentPos)
            } else {
                // すでに選択モードなら、前回長押しした場所から今回長押しした場所までを「範囲選択」
                if (lastLongPressedPosition != -1) {
                    val start = min(lastLongPressedPosition, currentPos)
                    val end = max(lastLongPressedPosition, currentPos)
                    for (i in start..end) {
                        selectedPositions.add(i)
                    }
                    lastLongPressedPosition = currentPos
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemRangeChanged(start, end - start + 1)
                } else {
                    lastLongPressedPosition = currentPos
                    selectedPositions.add(currentPos)
                    onSelectionCountChanged(selectedPositions.size)
                    notifyItemChanged(currentPos)
                }
            }
            true
        }
    }

    override fun getItemCount() = images.size
}
