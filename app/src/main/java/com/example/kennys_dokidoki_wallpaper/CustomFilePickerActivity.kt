package com.example.kennys_dokidoki_wallpaper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.json.JSONArray
import java.io.File
import kotlin.math.max
import kotlin.math.min

class CustomFilePickerActivity : AppCompatActivity() {

    enum class SortMode { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter
    private var currentDir: File? = null
    
    // 選択されたファイルの絶対パスを保持
    private val selectedFiles = mutableSetOf<String>()
    private val alreadyAddedUris = mutableSetOf<String>()
    
    private var lastInteractedPosition = -1
    private var currentSortMode = SortMode.NAME_ASC

    // お気に入りフォルダ機能用
    private val favoriteFolders = mutableListOf<String>()

    // View References
    private lateinit var btnSortName: TextView
    private lateinit var btnSortDate: TextView
    private lateinit var selectedCountText: TextView
    private lateinit var btnAddFavorite: ImageButton

    companion object {
        const val TYPE_FOLDER = 1
        const val TYPE_IMAGE = 2
        private const val PREFS_NAME = "picker_prefs"
        private const val KEY_FAVORITES = "favorite_folders"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_file_picker)

        loadFavorites()

        alreadyAddedUris.addAll(DataManager.allImages.map { it.uri.toString() })

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        btnSortName = findViewById(R.id.btn_sort_name)
        btnSortDate = findViewById(R.id.btn_sort_date)
        selectedCountText = findViewById(R.id.selected_count_text)
        btnAddFavorite = findViewById(R.id.btn_add_favorite_folder)

        recyclerView = findViewById(R.id.file_recycler_view)
        
        recyclerView.layoutManager = GridLayoutManager(this, 2)

        setupSortButtons()
        
        btnAddFavorite.setOnClickListener {
            currentDir?.let { dir ->
                val path = dir.absolutePath
                if (favoriteFolders.contains(path)) {
                    favoriteFolders.remove(path)
                    Toast.makeText(this, "お気に入りから削除しました", Toast.LENGTH_SHORT).show()
                } else {
                    favoriteFolders.add(path)
                    Toast.makeText(this, "このフォルダをお気に入りに追加しました。", Toast.LENGTH_SHORT).show()
                }
                saveFavorites()
                updateFavoriteButtonState()
            }
        }

        checkPermissionsAndStart()

        findViewById<View>(R.id.btn_add_selected).setOnClickListener {
            val uris = ArrayList(selectedFiles.map { Uri.fromFile(File(it)).toString() })
            val resultIntent = Intent().apply {
                putStringArrayListExtra("selected_uris", uris)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun loadFavorites() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FAVORITES, null)
        favoriteFolders.clear()
        if (json != null) {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                favoriteFolders.add(array.getString(i))
            }
        }
    }

    private fun saveFavorites() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray(favoriteFolders)
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private fun updateFavoriteButtonState() {
        if (currentDir == null) {
            btnAddFavorite.visibility = View.GONE
        } else {
            btnAddFavorite.visibility = View.VISIBLE
            val path = currentDir?.absolutePath
            if (favoriteFolders.contains(path)) {
                btnAddFavorite.setImageResource(android.R.drawable.btn_star_big_on) // 星塗りつぶし
            } else {
                btnAddFavorite.setImageResource(android.R.drawable.btn_star) // 星枠のみ
            }
        }
    }

    private fun setupSortButtons() {
        btnSortName.setOnClickListener {
            currentSortMode = if (currentSortMode == SortMode.NAME_ASC) SortMode.NAME_DESC else SortMode.NAME_ASC
            updateSortUI()
            currentDir?.let { updateFileList(it) } ?: showRoots()
        }

        btnSortDate.setOnClickListener {
            currentSortMode = if (currentSortMode == SortMode.DATE_DESC) SortMode.DATE_ASC else SortMode.DATE_DESC
            updateSortUI()
            currentDir?.let { updateFileList(it) } ?: showRoots()
        }
        updateSortUI()
    }

    private fun updateSortUI() {
        val activeColor = android.graphics.Color.parseColor("#00F0FF")
        val inactiveColor = android.graphics.Color.parseColor("#8892B0")

        when (currentSortMode) {
            SortMode.NAME_ASC -> {
                btnSortName.text = "名前 ▲"
                btnSortName.setTextColor(activeColor)
                btnSortDate.text = "更新日 -"
                btnSortDate.setTextColor(inactiveColor)
            }
            SortMode.NAME_DESC -> {
                btnSortName.text = "名前 ▼"
                btnSortName.setTextColor(activeColor)
                btnSortDate.text = "更新日 -"
                btnSortDate.setTextColor(inactiveColor)
            }
            SortMode.DATE_ASC -> {
                btnSortDate.text = "更新日 ▲"
                btnSortDate.setTextColor(activeColor)
                btnSortName.text = "名前 -"
                btnSortName.setTextColor(inactiveColor)
            }
            SortMode.DATE_DESC -> {
                btnSortDate.text = "更新日 ▼"
                btnSortDate.setTextColor(activeColor)
                btnSortName.text = "名前 -"
                btnSortName.setTextColor(inactiveColor)
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            showRoots()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showRoots()
        } else {
            Toast.makeText(this, "権限がないと画像が見れないよ！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRoots() {
        currentDir = null
        title = "ストレージ・お気に入り選択"
        updateFavoriteButtonState()
        
        val roots = mutableListOf<File>()
        
        // お気に入りフォルダを先頭に追加（ダミー的なFileオブジェクトとして扱うか、実在確認する）
        favoriteFolders.forEach { path ->
            val f = File(path)
            if (f.exists() && f.isDirectory) {
                roots.add(f)
            }
        }
        
        roots.add(Environment.getExternalStorageDirectory())

        val extDirs = getExternalFilesDirs(null)
        if (extDirs.size > 1) {
            extDirs[1]?.let { file ->
                val path = file.absolutePath
                val storageIdx = path.indexOf("/Android")
                if (storageIdx != -1) {
                    roots.add(File(path.substring(0, storageIdx)))
                }
            }
        }

        lastInteractedPosition = -1
        adapter = FileAdapter(roots, false, isRoot = true)
        recyclerView.adapter = adapter
    }

    private fun updateFileList(dir: File) {
        currentDir = dir
        title = dir.name.ifEmpty { dir.absolutePath }
        updateFavoriteButtonState()
        
        val allFiles = dir.listFiles()

        if (allFiles == null) {
            Toast.makeText(this, "このフォルダにはアクセスできないみたい。", Toast.LENGTH_SHORT).show()
            adapter = FileAdapter(emptyList(), true)
            recyclerView.adapter = adapter
            return
        }

        val filteredFiles = allFiles.filter { it.isDirectory || isImageFile(it) }

        val sortedFiles = filteredFiles.sortedWith { f1, f2 ->
            if (f1.isDirectory != f2.isDirectory) {
                if (f1.isDirectory) -1 else 1
            } else {
                when (currentSortMode) {
                    SortMode.NAME_ASC -> f1.name.lowercase().compareTo(f2.name.lowercase())
                    SortMode.NAME_DESC -> f2.name.lowercase().compareTo(f1.name.lowercase())
                    SortMode.DATE_ASC -> f1.lastModified().compareTo(f2.lastModified())
                    SortMode.DATE_DESC -> f2.lastModified().compareTo(f1.lastModified())
                }
            }
        }

        lastInteractedPosition = -1
        adapter = FileAdapter(sortedFiles, true)
        recyclerView.adapter = adapter
    }

    private fun isImageFile(file: File): Boolean {
        val extensions = listOf("jpg", "jpeg", "png", "gif", "webp")
        return file.isFile && extensions.contains(file.extension.lowercase())
    }

    private fun toggleSelection(file: File) {
        val path = file.absolutePath
        if (selectedFiles.contains(path)) {
            selectedFiles.remove(path)
        } else {
            selectedFiles.add(path)
        }
        selectedCountText.text = "選択中: ${selectedFiles.size}"
    }

    override fun onBackPressed() {
        if (currentDir != null) {
            val parent = currentDir?.parentFile
            if (parent == null || currentDir?.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
                showRoots()
            } else {
                updateFileList(parent)
            }
        } else {
            super.onBackPressed()
        }
    }

    inner class FileAdapter(
        private val files: List<File>,
        private val showBackButton: Boolean,
        private val isRoot: Boolean = false
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            if (showBackButton && position == 0) return TYPE_FOLDER
            val file = if (showBackButton) files[position - 1] else files[position]
            return if (file.isDirectory || isRoot) TYPE_FOLDER else TYPE_IMAGE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_FOLDER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_picker_folder, parent, false)
                FolderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_picker_image, parent, false)
                ImageViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val isBackBtn = showBackButton && position == 0
            val file = if (showBackButton && !isBackBtn) files[position - 1] else if (!isBackBtn) files[position] else null

            if (holder is FolderViewHolder) {
                if (isBackBtn) {
                    holder.name.text = "上の階層へ戻る"
                    holder.icon.setImageResource(android.R.drawable.ic_menu_revert)
                    holder.itemView.setOnClickListener { 
                        val parent = currentDir?.parentFile
                        if (parent == null || currentDir?.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
                            showRoots()
                        } else {
                            updateFileList(parent)
                        }
                    }
                } else if (file != null) {
                    if (isRoot) {
                        // お気に入りか、標準のストレージか判定
                        if (favoriteFolders.contains(file.absolutePath)) {
                            holder.name.text = "★ ${file.name}"
                            holder.name.setTextColor(android.graphics.Color.parseColor("#FFD700"))
                            holder.icon.setImageResource(android.R.drawable.btn_star_big_on)
                        } else {
                            holder.name.text = if (file.absolutePath == Environment.getExternalStorageDirectory().absolutePath) "内部ストレージ" else "SDカード"
                            holder.name.setTextColor(android.graphics.Color.WHITE)
                            holder.icon.setImageResource(android.R.drawable.ic_dialog_info)
                        }
                    } else {
                        holder.name.text = file.name
                        holder.name.setTextColor(android.graphics.Color.WHITE)
                        holder.icon.setImageResource(android.R.drawable.ic_menu_agenda)
                    }
                    holder.itemView.setOnClickListener { 
                        if (file.isDirectory) updateFileList(file) 
                    }
                }
            } else if (holder is ImageViewHolder && file != null) {
                holder.name.text = file.name

                Glide.with(holder.thumbnail.context)
                    .load(file)
                    .centerCrop()
                    .into(holder.thumbnail)

                val uriStr = Uri.fromFile(file).toString()
                val isAlreadyAdded = alreadyAddedUris.contains(uriStr)
                val isSelectedNow = selectedFiles.contains(file.absolutePath)

                if (isAlreadyAdded) {
                    holder.dimOverlay.visibility = View.VISIBLE
                    holder.checkIcon.visibility = View.VISIBLE
                    holder.selectionOverlay.visibility = View.GONE
                    holder.selectedCheck.visibility = View.GONE
                    
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    holder.dimOverlay.visibility = View.GONE
                    holder.checkIcon.visibility = View.GONE
                    
                    if (isSelectedNow) {
                        holder.selectionOverlay.visibility = View.VISIBLE
                        holder.selectedCheck.visibility = View.VISIBLE
                    } else {
                        holder.selectionOverlay.visibility = View.GONE
                        holder.selectedCheck.visibility = View.GONE
                    }

                    // タップで選択のトグル
                    holder.itemView.setOnClickListener {
                        val pos = holder.adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            toggleSelection(file)
                            lastInteractedPosition = pos
                            notifyItemChanged(pos)
                        }
                    }

                    // 長押しで範囲選択
                    holder.itemView.setOnLongClickListener {
                        val pos = holder.adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            if (lastInteractedPosition != -1 && lastInteractedPosition != pos) {
                                val start = min(lastInteractedPosition, pos)
                                val end = max(lastInteractedPosition, pos)
                                for (i in start..end) {
                                    val targetFile = if (showBackButton) files[i - 1] else files[i]
                                    if (!targetFile.isDirectory && isImageFile(targetFile)) {
                                        val tUriStr = Uri.fromFile(targetFile).toString()
                                        if (!alreadyAddedUris.contains(tUriStr)) {
                                            selectedFiles.add(targetFile.absolutePath)
                                        }
                                    }
                                }
                                lastInteractedPosition = pos
                                selectedCountText.text = "選択中: ${selectedFiles.size}"
                                notifyItemRangeChanged(start, end - start + 1)
                            } else {
                                // 単純に選択に追加
                                selectedFiles.add(file.absolutePath)
                                lastInteractedPosition = pos
                                selectedCountText.text = "選択中: ${selectedFiles.size}"
                                notifyItemChanged(pos)
                            }
                        }
                        true
                    }
                }
            }
        }

        override fun getItemCount() = if (showBackButton) files.size + 1 else files.size

        inner class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.folder_icon)
            val name: TextView = view.findViewById(R.id.folder_name)
        }

        inner class ImageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.image_thumbnail)
            val name: TextView = view.findViewById(R.id.image_name)
            val dimOverlay: View = view.findViewById(R.id.dim_overlay)
            val checkIcon: ImageView = view.findViewById(R.id.check_icon)
            val selectionOverlay: View = view.findViewById(R.id.selection_overlay)
            val selectedCheck: ImageView = view.findViewById(R.id.selected_check)
        }
    }
}
