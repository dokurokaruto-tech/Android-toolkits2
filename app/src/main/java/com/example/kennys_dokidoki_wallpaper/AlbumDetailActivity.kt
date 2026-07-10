package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class AlbumDetailActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val RESULT_GO_TO_FOLDER_PICKER = 1001
    }

    private lateinit var imageAdapter: ImageAdapter
    private val images = mutableListOf<ImageEntry>()
    private var albumName: String = ""
    private var isSortAscending: Boolean = true

    // 複数選択用アクションバー
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var recyclerView: RecyclerView
    private var isGeneratedViewer: Boolean = false
    private val virtualImages = mutableListOf<ImageEntry>()

    private val previewLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val finalIndex = result.data?.getIntExtra("FINAL_INDEX", -1) ?: -1
            if (finalIndex != -1) {
                // カルーセルで見ていた画像の位置まで、アルバム一覧もスクロールさせるわよ！
                recyclerView.scrollToPosition(finalIndex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_detail)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { 
            if (imageAdapter.isSelectionMode) {
                imageAdapter.stopSelectionMode()
            } else if (albumName.startsWith("生成:")) {
                setResult(RESULT_GO_TO_FOLDER_PICKER)
                finish()
            } else {
                finish()
            }
        }

        albumName = intent.getStringExtra("ALBUM_NAME") ?: "アルバム"
        title = albumName

        val virtualUris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS")
        isGeneratedViewer = virtualUris != null

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        isSortAscending = settingsPrefs.getBoolean("sort_ascending", true)

        selectionActionBar = findViewById(R.id.selection_action_bar)
        tvSelectionCount = findViewById(R.id.tv_selection_count)

        loadImages()

        recyclerView = findViewById(R.id.image_recycler_view)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        
        imageAdapter = ImageAdapter(
            images = images,
            onImageClick = { position, entry, _ ->
                if (isGeneratedViewer) {
                    // 生成画像閲覧モードならカルーセルを飛ばして全画面表示へ！
                    val intent = Intent(this, FullScreenImageActivity::class.java).apply {
                        putExtra("ALBUM_NAME", albumName)
                        putExtra("START_INDEX", position)
                        if (isGeneratedViewer) {
                            putStringArrayListExtra("VIRTUAL_ALBUM_URIS", ArrayList(images.map { it.uri.toString() }))
                        }
                    }
                    startActivity(intent)
                } else {
                    // 通常モードなら今まで通りカルーセル
                    val intent = Intent(this, ImagePreviewActivity::class.java).apply {
                        putExtra("ALBUM_NAME", albumName)
                        putExtra("START_INDEX", position)
                    }
                    previewLauncher.launch(intent)
                }
            },
            onDeleteClick = { entry, _ ->
                // 削除処理（生成画像モードでも、登録済みの場合はDataManagerから、未登録ならファイル削除のみ）
                AlertDialog.Builder(this)
                    .setTitle("画像の削除")
                    .setMessage("このファイルを完全に削除しますか？")
                    .setNeutralButton("削除する") { _, _ ->
                        val success = DataManager.deleteImageFile(this, entry.uri)
                        if (success) {
                            // DataManagerからも確実に抹消する
                            DataManager.allImages.removeAll { it.uri.toString() == entry.uri.toString() }
                            DataManager.saveData(this)
                            
                            loadImages()
                            imageAdapter.notifyDataSetChanged()
                            Toast.makeText(this, "ファイルを削除しました。", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "削除に失敗しました。", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            },
            onStartWallpaperClick = { _ -> }, // モードにより不要
            onEditTagsClick = { _ -> }, // モードにより不要
            onSelectionModeChanged = { isSelectionMode ->
                if (isSelectionMode) {
                    selectionActionBar.visibility = View.VISIBLE
                    toolbar.visibility = View.GONE
                } else {
                    selectionActionBar.visibility = View.GONE
                    toolbar.visibility = View.VISIBLE
                }
            },
            onSelectionCountChanged = { count ->
                tvSelectionCount.text = "${count}件選択中"
            }
        )
        imageAdapter.isGeneratedViewerMode = isGeneratedViewer
        recyclerView.adapter = imageAdapter
        recyclerView.addItemDecoration(CurrentWallpaperIndicatorDecoration(
            getCurrentIndex = { imageAdapter.getActiveImageIndex() },
            getItemCount = { imageAdapter.itemCount }
        ))

        setupSelectionBarButtons()
        setupFastScrollJumper(recyclerView)

        settingsPrefs.registerOnSharedPreferenceChangeListener(this)
        
        updateActiveImageHighlight()

        val dataPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        dataPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val sortItem = menu.add(0, 100, 0, "並び替え順")
        sortItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        updateSortMenuIcon(sortItem)
        return true
    }

    private fun updateSortMenuIcon(item: MenuItem) {
        if (isSortAscending) {
            item.setIcon(android.R.drawable.arrow_up_float)
        } else {
            item.setIcon(android.R.drawable.arrow_down_float)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 100) {
            isSortAscending = !isSortAscending
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("sort_ascending", isSortAscending).apply()
            updateSortMenuIcon(item)
            loadImages()
            imageAdapter.notifyDataSetChanged()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupFastScrollJumper(rv: RecyclerView) {
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val threshold = rv.width - 100
                if (e.x > threshold) {
                    jumpToPosition(rv, e.y)
                    return true
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_DOWN) {
                    jumpToPosition(rv, e.y)
                }
            }

            private fun jumpToPosition(rv: RecyclerView, touchY: Float) {
                val adapter = rv.adapter ?: return
                val count = adapter.itemCount
                if (count == 0) return
                val percentage = (touchY / rv.height).coerceIn(0f, 1f)
                val position = (percentage * (count - 1)).toInt()
                val layoutManager = rv.layoutManager
                if (layoutManager is LinearLayoutManager) {
                    layoutManager.scrollToPositionWithOffset(position, 0)
                } else {
                    rv.scrollToPosition(position)
                }
            }
        })
    }

    private fun setupSelectionBarButtons() {
        findViewById<ImageButton>(R.id.btn_selection_close).setOnClickListener {
            imageAdapter.stopSelectionMode()
        }
        
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            imageAdapter.selectAll()
        }

        val btnImport = findViewById<ImageButton>(R.id.btn_selection_tag)
        if (isGeneratedViewer) {
            // 自動登録されてるなら、手動登録ボタンは不要ね！非表示にするわ！
            btnImport.visibility = View.GONE
        } else {
            btnImport.setOnClickListener {
                showBatchTagPicker()
            }
        }

        findViewById<ImageButton>(R.id.btn_selection_inspect).setOnClickListener {
            showInspectionCategorySelector()
        }

        findViewById<ImageButton>(R.id.btn_selection_delete).setOnClickListener {
            val selectedEntries = imageAdapter.getSelectedEntries()
            if (selectedEntries.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("一括削除")
                .setMessage("${selectedEntries.size}件の画像をどうする？")
                .setPositiveButton("リストから外す") { _, _ ->
                    DataManager.allImages.removeAll(selectedEntries)
                    DataManager.saveData(this)
                    imageAdapter.stopSelectionMode()
                    loadImages()
                    imageAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "${selectedEntries.size}件をリストから除外しました。", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("ファイルごと全て削除") { _, _ ->
                    var count = 0
                    selectedEntries.forEach { 
                        if (DataManager.deleteImageFile(this, it.uri)) count++
                    }
                    DataManager.allImages.removeAll(selectedEntries)
                    DataManager.saveData(this)
                    imageAdapter.stopSelectionMode()
                    loadImages()
                    imageAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "${count}件のファイルを削除しました。", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private fun showBatchTagPicker() {
        val selectedEntries = imageAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) return
        
        val uris = ArrayList(selectedEntries.map { it.uri.toString() })
        val intent = Intent(this, ImageTagEditorActivity::class.java).apply {
            putStringArrayListExtra("SELECTED_URIS", uris)
        }
        startActivity(intent)
        imageAdapter.stopSelectionMode()
    }

    private fun showInspectionCategorySelector() {
        val selectedEntries = imageAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) return

        val categoryNames = TagManager.categories.map { it.name }.toTypedArray()
        val checkedItems = BooleanArray(categoryNames.size) { true }
        
        AlertDialog.Builder(this)
            .setTitle("どの項目を検査する？")
            .setMultiChoiceItems(categoryNames, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("検査開始") { _, _ ->
                val targetCategories = ArrayList<String>()
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) targetCategories.add(categoryNames[i])
                }
                
                if (targetCategories.isEmpty()) {
                    Toast.makeText(this, "検査項目を選択してください。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val uris = ArrayList(selectedEntries.map { it.uri.toString() })
                val intent = Intent(this, InspectionActivity::class.java).apply {
                    putStringArrayListExtra("IMAGE_URIS", uris)
                    putStringArrayListExtra("TARGET_CATEGORIES", targetCategories)
                }
                startActivity(intent)
                imageAdapter.stopSelectionMode()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startWallpaper(entry: ImageEntry) {
        if (!entry.isActive) {
            entry.isActive = true
            DataManager.saveData(this)
        }
        
        val currentSet = DataManager.imageSetList.find { it.name == albumName }
        if (currentSet != null) {
            val activeEntries = currentSet.filterImages(DataManager.allImages).filter { it.isActive }
            val activeIndex = activeEntries.indexOf(entry)

            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("active_album_name", albumName)
                val finalIndex = if (activeIndex != -1) activeIndex else 0
                putInt("active_image_index", finalIndex)
                putInt("last_index_for_album_$albumName", finalIndex)
                apply()
            }
            
            val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    android.content.ComponentName(this@AlbumDetailActivity, MyWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(this)
        
        val dataPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        dataPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "all_images" || key == "image_sets" || key == "active_image_index" || key == "active_album_name" || key == "sort_ascending") {
            runOnUiThread {
                DataManager.loadData(this)
                if (key == "sort_ascending") {
                    isSortAscending = sharedPreferences?.getBoolean("sort_ascending", true) ?: true
                    invalidateOptionsMenu()
                }
                loadImages()
                updateActiveImageHighlight()
                imageAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateActiveImageHighlight() {
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val activeAlbum = settingsPrefs.getString("active_album_name", null)
        
        if (activeAlbum == albumName) {
            val activeIndex = settingsPrefs.getInt("active_image_index", 0)
            val currentSet = DataManager.imageSetList.find { it.name == albumName }
            if (currentSet != null) {
                val activeEntries = currentSet.filterImages(DataManager.allImages).filter { it.isActive }
                if (activeIndex in activeEntries.indices) {
                    imageAdapter.activeImageUri = activeEntries[activeIndex].uri.toString()
                } else {
                    imageAdapter.activeImageUri = null
                }
            }
        } else {
            imageAdapter.activeImageUri = null
        }
        
        if (::recyclerView.isInitialized) {
            recyclerView.invalidateItemDecorations()
        }
    }

    private fun loadImages() {
        if (isGeneratedViewer) {
            val folderUriStr = intent.getStringExtra("FOLDER_URI") ?: return
            val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
            val files = folder.listFiles()
                .filter { it.isFile && (it.type?.startsWith("image/") == true || it.name?.endsWith(".png") == true || it.name?.endsWith(".jpg") == true) }
                .sortedByDescending { it.name }
            
            images.clear()
            files.forEach { file ->
                images.add(ImageEntry(file.uri))
            }
            if (!isSortAscending) images.reverse()
        } else {
            val currentSet = DataManager.imageSetList.find { it.name == albumName }
            if (currentSet != null) {
                val baseList = currentSet.filterImages(DataManager.allImages)
                images.clear()
                images.addAll(if (isSortAscending) baseList else baseList.reversed())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateActiveImageHighlight()
        imageAdapter.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (imageAdapter.isSelectionMode) {
            imageAdapter.stopSelectionMode()
        } else if (albumName.startsWith("生成:")) {
            setResult(RESULT_GO_TO_FOLDER_PICKER)
            finish()
        } else {
            super.onBackPressed()
        }
    }
}