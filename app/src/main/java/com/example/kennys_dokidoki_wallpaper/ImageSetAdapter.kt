package com.example.kennys_dokidoki_wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton

class ImageSetAdapter(private val sets: MutableList<ImageSet>) :
    RecyclerView.Adapter<ImageSetAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ImageView = view.findViewById(R.id.album_thumbnail)
        val name: TextView = view.findViewById(R.id.album_name)
        val count: TextView = view.findViewById(R.id.album_photo_count)
        val card: View = view.findViewById(R.id.album_card)
        val btnSetWallpaper: MaterialButton = view.findViewById(R.id.btn_set_wallpaper)
        val btnMore: ImageView = view.findViewById(R.id.btn_more_album)
        val activeIcon: ImageView = view.findViewById(R.id.album_active_icon)
        val iconCropped: ImageView = view.findViewById(R.id.icon_cropped_album)
        val badgeHomescreen: TextView = view.findViewById(R.id.badge_homescreen)
        val badgeChat: TextView = view.findViewById(R.id.badge_chat)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_album, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val context = holder.itemView.context
        val set = sets[position]
        val filteredEntries = set.filterImages(DataManager.allImages)
        val activeEntries = filteredEntries.filter { it.isActive }
        
        val usageDesc = when (set.usage) {
            ImageSetUsage.HOMESCREEN -> " [ホーム]"
            ImageSetUsage.CHAT -> " [チャット]"
            ImageSetUsage.BOTH -> " [両方]"
        }
        holder.name.text = "${set.name}$usageDesc"
        val matchDesc = if (set.matchMode == MatchMode.ALL) "[完全一致]" else "[部分一致]"
        val tagsText = if (set.targetTags.isEmpty()) {
            context.getString(R.string.tags_not_set)
        } else {
            set.targetTags.joinToString(", ")
        }
        
        holder.count.text = context.getString(
            R.string.album_info_format,
            filteredEntries.size,
            matchDesc,
            tagsText
        )
        
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lastIndex = prefs.getInt("last_index_for_album_${set.name}", 0)
        
        val displayEntries = activeEntries.ifEmpty { filteredEntries }
        
        if (displayEntries.isNotEmpty()) {
            val safeIndex = lastIndex.coerceIn(0, displayEntries.size - 1)
            val selectedEntry = displayEntries[safeIndex]
            val imageToLoad = selectedEntry.uri
            
            Glide.with(holder.thumbnail.context)
                .load(imageToLoad)
                .override(600, 1066)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.thumbnail)
                
            holder.iconCropped.visibility = if (selectedEntry.cropRect != null || selectedEntry.croppedUri != null) View.VISIBLE else View.GONE
        } else {
            holder.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery)
            holder.thumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.iconCropped.visibility = View.GONE
        }

        // アクティブな適用バッジの表示切り替え
        val activeAlbumHomescreen = prefs.getString("active_album_name_homescreen", null)
            ?: prefs.getString("active_album_name", null)
        val activeAlbumChat = prefs.getString("active_album_name_chat", null)
            ?: prefs.getString("active_album_name", null)
        
        val canShowHomescreenBadge = set.usage == ImageSetUsage.HOMESCREEN || set.usage == ImageSetUsage.BOTH
        val canShowChatBadge = set.usage == ImageSetUsage.CHAT || set.usage == ImageSetUsage.BOTH

        holder.badgeHomescreen.visibility = if (canShowHomescreenBadge && set.name == activeAlbumHomescreen) View.VISIBLE else View.GONE
        holder.badgeChat.visibility = if (canShowChatBadge && set.name == activeAlbumChat) View.VISIBLE else View.GONE

        if (set.isActive) {
            holder.activeIcon.setImageResource(R.drawable.ic_cyber_check)
        } else {
            holder.activeIcon.setImageResource(R.drawable.ic_cyber_uncheck)
        }

        holder.activeIcon.setOnClickListener {
            set.isActive = !set.isActive
            if (set.isActive) {
                holder.activeIcon.setImageResource(R.drawable.ic_cyber_check)
            } else {
                holder.activeIcon.setImageResource(R.drawable.ic_cyber_uncheck)
            }
            DataManager.saveData(it.context)
        }
        
        holder.card.setOnClickListener {
            val intent = Intent(it.context, AlbumDetailActivity::class.java).apply {
                putExtra("ALBUM_NAME", set.name)
            }
            it.context.startActivity(intent)
        }

        holder.btnSetWallpaper.setOnClickListener {
            if (filteredEntries.isEmpty()) {
                Toast.makeText(it.context, "条件に合う画像がないよ！タグを見直しなよｗ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentLastIndex = prefs.getInt("last_index_for_album_${set.name}", 0)
            val targetEntries = activeEntries.ifEmpty { filteredEntries }
            val safeIndex = currentLastIndex.coerceIn(0, targetEntries.size - 1)

            set.isActive = true
            holder.activeIcon.setImageResource(R.drawable.ic_cyber_check)
            // ここで先に保存する！
            DataManager.saveData(it.context)

            val usageType = set.usage
            prefs.edit().apply {
                when (usageType) {
                    ImageSetUsage.HOMESCREEN -> {
                        putString("active_album_name_homescreen", set.name)
                        putInt("active_image_index", safeIndex)
                        putInt("last_index_for_album_${set.name}", safeIndex)
                    }
                    ImageSetUsage.CHAT -> {
                        putString("active_album_name_chat", set.name)
                        putInt("last_index_for_album_${set.name}", safeIndex)
                    }
                    ImageSetUsage.BOTH -> {
                        putString("active_album_name_homescreen", set.name)
                        putString("active_album_name_chat", set.name)
                        putString("active_album_name", set.name)
                        putInt("active_image_index", safeIndex)
                        putInt("last_index_for_album_${set.name}", safeIndex)
                    }
                }
                apply()
            }
            notifyDataSetChanged()

            // どのusageTypeであっても、真ん中の再生ボタンを押したら絶対に『set wallpaper』の画面が出るようにする
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(it.context, MyWallpaperService::class.java)
                )
            }
            it.context.startActivity(intent)
        }

        holder.btnMore.setOnClickListener { view ->
            val popup = PopupMenu(view.context, view)
            popup.menu.add("イメージセットの編集")
            popup.menu.add("セットを削除")
            
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "イメージセットの編集" -> {
                        // AdapterPosition を使って確実に現在のセット名を渡すように修正
                        val currentSet = sets[holder.adapterPosition]
                        val intent = Intent(view.context, ImageTagEditorActivity::class.java)
                        intent.putExtra("SET_NAME", currentSet.name)
                        intent.putExtra("CREATE_NEW_SET", false)
                        view.context.startActivity(intent)
                    }
                    "セットを削除" -> showDeleteDialog(view.context, sets[holder.adapterPosition], holder.adapterPosition)
                }
                true
            }
            popup.show()
        }
    }

    private fun showDeleteDialog(context: Context, set: ImageSet, position: Int) {
        AlertDialog.Builder(context)
            .setTitle("セットを消すの？")
            .setMessage("「${set.name}」を消しても、画像自体は消えないから安心してｗ")
            .setPositiveButton("削除") { _, _ ->
                if (position != RecyclerView.NO_POSITION) {
                    sets.removeAt(position)
                    notifyItemRemoved(position)
                    DataManager.saveData(context)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun getItemCount() = sets.size
}
