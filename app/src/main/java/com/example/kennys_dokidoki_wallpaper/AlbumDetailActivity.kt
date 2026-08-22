package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AlbumDetailActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var imageAdapter: ImageAdapter
    private val images = mutableListOf<ImageEntry>()
    private var albumName: String = ""
    private var isSortAscending: Boolean = true

    // 複数選択用アクションバー
    private lateinit var selectionActionBar: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var recyclerView: RecyclerView
    private var isGeneratedViewer: Boolean = false
    private var isRemoteGenerated: Boolean = false
    private var remoteDate: String = "downloaded"
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
                closeGeneratedAlbum()
            } else {
                finish()
            }
        }

        albumName = intent.getStringExtra("ALBUM_NAME") ?: "アルバム"
        title = albumName

        val virtualUris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS")
        isGeneratedViewer = virtualUris != null
        isRemoteGenerated = intent.getBooleanExtra("REMOTE_GENERATED", false)
        remoteDate = intent.getStringExtra("REMOTE_DATE") ?: "downloaded"

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
                        putExtra("FROM_GENERATED_VIEWER", true)
                        putExtra("REMOTE_GENERATED", isRemoteGenerated)
                        putExtra("REMOTE_DATE", remoteDate)
                        putStringArrayListExtra("VIRTUAL_ALBUM_URIS", ArrayList(images.map { it.uri.toString() }))
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
                confirmDeleteGeneratedOrLibrary(listOf(entry))
            },
            onStartWallpaperClick = { entry -> startWallpaper(entry) },
            onEditTagsClick = { entry ->
                val intent = Intent(this, ImageTagEditorActivity::class.java)
                intent.putExtra("IMAGE_URI", entry.uri.toString())
                if (isGeneratedViewer) {
                    intent.putExtra("GENERATED_DRAFT", true)
                    entry.thumbnailUri?.let { intent.putExtra("THUMBNAIL_URI", it.toString()) }
                }
                startActivity(intent)
            },
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
        FastScrollHelper.attach(recyclerView)

        settingsPrefs.registerOnSharedPreferenceChangeListener(this)
        
        updateActiveImageHighlight()

        val dataPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
        dataPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (isGeneratedViewer) {
            // 生成画像ビューワー: 「全画像に入れる」をケバブに用意
            val importTitle = if (isRemoteGenerated) "端末へダウンロードして全画像に入れる" else "全画像に入れる"
            val importItem = menu.add(0, 101, 0, importTitle)
            importItem.setIcon(android.R.drawable.ic_menu_add)
            importItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        } else {
            val sortItem = menu.add(0, 100, 0, "並び替え順")
            sortItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            updateSortMenuIcon(sortItem)
        }
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
        if (item.itemId == 101) {
            // 全画像に入れる：選択中があればその分、なければフォルダ内すべて
            val selected = imageAdapter.getSelectedEntries()
            val targets = if (imageAdapter.isSelectionMode && selected.isNotEmpty()) selected else images.toList()
            confirmAddToAllImages(targets)
            return true
        }
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



    private fun setupSelectionBarButtons() {
        findViewById<ImageButton>(R.id.btn_selection_close).setOnClickListener {
            imageAdapter.stopSelectionMode()
        }
        
        findViewById<Button>(R.id.btn_select_all).setOnClickListener {
            imageAdapter.selectAll()
        }

        val btnImport = findViewById<ImageButton>(R.id.btn_selection_tag)
        if (isGeneratedViewer) {
            // 生成画像ビューワー: 選択した画像を「全画像に入れる」ボタンにする
            btnImport.visibility = View.VISIBLE
            btnImport.setImageResource(android.R.drawable.ic_menu_add)
            btnImport.setOnClickListener {
                val selected = imageAdapter.getSelectedEntries()
                if (selected.isEmpty()) {
                    Toast.makeText(this, "画像を選択してください", Toast.LENGTH_SHORT).show()
                } else {
                    confirmAddToAllImages(selected)
                }
            }
        } else {
            btnImport.setOnClickListener {
                showBatchTagPicker()
            }
        }

        val btnInspect = findViewById<ImageButton>(R.id.btn_selection_inspect)
        btnInspect.setOnClickListener { showInspectionCategorySelector() }

        val btnDelete = findViewById<ImageButton>(R.id.btn_selection_delete)
        btnDelete.setOnClickListener {
            val selectedEntries = imageAdapter.getSelectedEntries()
            if (selectedEntries.isEmpty()) return@setOnClickListener

            if (isGeneratedViewer) {
                confirmDeleteGeneratedOrLibrary(selectedEntries)
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("一括削除")
                .setMessage("${selectedEntries.size}件の画像をどうする？")
                .setPositiveButton("リストから外す") { _, _ ->
                    selectedEntries.forEach { GeneratedImageDraftStore.deleteImageAndMaybeChat(this, it.uri) }
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
                        if (DataManager.deleteImageFile(this, it.uri)) {
                            GeneratedImageDraftStore.deleteImageAndMaybeChat(this, it.uri)
                            count++
                        }
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
        if (isRemoteGenerated) {
            btnInspect.visibility = View.GONE
        }
        if (isGeneratedViewer) {
            btnDelete.visibility = View.VISIBLE
        }
    }

    private fun confirmDeleteGeneratedOrLibrary(targets: List<ImageEntry>) {
        if (targets.isEmpty()) return
        val message = when {
            isGeneratedViewer && isRemoteGenerated ->
                "${targets.size}件をPCからも削除します。紐づいた仮チャットも消えます。"
            isGeneratedViewer ->
                "${targets.size}件の生成画像を削除します。紐づいた仮チャットも消えます。"
            else -> "このファイルを完全に削除しますか？"
        }
        AlertDialog.Builder(this)
            .setTitle("画像の削除")
            .setMessage(message)
            .setNeutralButton("削除する") { _, _ ->
                if (isGeneratedViewer) {
                    deleteGeneratedImages(targets)
                } else {
                    var successCount = 0
                    targets.forEach { entry ->
                        if (DataManager.deleteImageFile(this, entry.uri)) {
                            GeneratedImageDraftStore.deleteImageAndMaybeChat(this, entry.uri)
                            DataManager.allImages.removeAll { it.uri.toString() == entry.uri.toString() }
                            successCount++
                        }
                    }
                    DataManager.saveData(this)
                    loadImages()
                    imageAdapter.notifyDataSetChanged()
                    if (successCount > 0) {
                        Toast.makeText(this, "${successCount}件のファイルを削除しました。", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "削除に失敗しました。", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteGeneratedImages(targets: List<ImageEntry>) {
        lifecycleScope.launch {
            var success = 0
            var failed = 0
            targets.forEach { entry ->
                val deleted = try {
                    if (isRemoteGenerated || ImageStoragePolicy.isRemote(entry.uri)) {
                        GenerationAgentClient.deleteLibraryImage(this@AlbumDetailActivity, entry.uri)
                    } else {
                        DataManager.deleteImageFile(this@AlbumDetailActivity, entry.uri)
                    }
                } catch (_: Exception) {
                    false
                }
                if (deleted) {
                    GeneratedImageDraftStore.deleteImageAndMaybeChat(this@AlbumDetailActivity, entry.uri)
                    DataManager.allImages.removeAll { it.uri.toString() == entry.uri.toString() }
                    forgetVirtualUri(entry.uri.toString())
                    success++
                } else {
                    failed++
                }
            }
            if (success > 0) DataManager.saveData(this@AlbumDetailActivity)
            if (imageAdapter.isSelectionMode) imageAdapter.stopSelectionMode()
            loadImages()
            imageAdapter.notifyDataSetChanged()
            val text = when {
                failed == 0 -> "${success}件を削除しました。"
                success == 0 -> "削除に失敗しました。"
                else -> "${success}件を削除、${failed}件は失敗しました。"
            }
            Toast.makeText(this@AlbumDetailActivity, text, Toast.LENGTH_LONG).show()
        }
    }

    /** 選択画像（または指定画像）を全画像に追加する。重複はスキップ。 */
    private fun confirmAddToAllImages(targets: List<ImageEntry>) {
        if (targets.isEmpty()) {
            Toast.makeText(this, "対象の画像がありません", Toast.LENGTH_SHORT).show()
            return
        }
        val message = if (isRemoteGenerated)
            "${targets.size}件をPCから端末の生成画像フォルダへダウンロードし、全画像に追加しますか？"
        else "${targets.size}件の画像を全画像に追加しますか？"
        AlertDialog.Builder(this)
            .setTitle(if (isRemoteGenerated) "ダウンロードして全画像に入れる" else "全画像に入れる")
            .setMessage(message)
            .setPositiveButton("追加") { _, _ ->
                if (isRemoteGenerated) {
                    lifecycleScope.launch {
                        var downloaded = 0
                        var added = 0
                        Toast.makeText(this@AlbumDetailActivity, "ダウンロード中...", Toast.LENGTH_SHORT).show()
                        targets.forEach { entry ->
                            val localUri = GeneratedImageImporter.download(this@AlbumDetailActivity, entry.uri, remoteDate)
                            if (localUri != null) {
                                downloaded++
                                val existing = DataManager.allImages.find { it.uri.toString() == localUri.toString() }
                                val imported = GeneratedImageDraftStore.migrateOnImport(
                                    this@AlbumDetailActivity, entry.uri, localUri
                                )
                                if (existing == null) {
                                    DataManager.allImages.add(0, imported)
                                    added++
                                } else {
                                    if (existing.tags.isEmpty()) existing.tags.addAll(imported.tags)
                                    if (existing.description.isNullOrBlank()) existing.description = imported.description
                                    if (existing.linkedChatId.isNullOrBlank()) existing.linkedChatId = imported.linkedChatId
                                }
                            }
                        }
                        if (added > 0) DataManager.saveData(this@AlbumDetailActivity)
                        if (imageAdapter.isSelectionMode) imageAdapter.stopSelectionMode()
                        val result = if (downloaded > 0) "${downloaded}件を端末へ保存（全画像へ新規追加: ${added}件）"
                        else "保存できませんでした。設定で『全画像に入れる』画像の保存先を確認してください。"
                        Toast.makeText(this@AlbumDetailActivity, result, Toast.LENGTH_LONG).show()
                    }
                } else {
                    var added = 0
                    targets.forEach { entry ->
                        val imported = GeneratedImageDraftStore.migrateOnImport(this, entry.uri, entry.uri)
                        if (DataManager.allImages.none { it.uri.toString() == imported.uri.toString() }) {
                            DataManager.allImages.add(0, imported)
                            added++
                        } else {
                            val existing = DataManager.allImages.first { it.uri.toString() == imported.uri.toString() }
                            if (existing.tags.isEmpty()) existing.tags.addAll(imported.tags)
                            if (existing.description.isNullOrBlank()) existing.description = imported.description
                            if (existing.linkedChatId.isNullOrBlank()) existing.linkedChatId = imported.linkedChatId
                        }
                    }
                    if (added > 0) DataManager.saveData(this)
                    if (imageAdapter.isSelectionMode) imageAdapter.stopSelectionMode()
                    Toast.makeText(this, "${added}件を全画像に追加しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
        // 現在壁紙になっている画像（このセット内）に外枠を付ける
        imageAdapter.activeImageUri = DataManager.currentWallpaperUri(this, images)
        if (::recyclerView.isInitialized) {
            recyclerView.invalidateItemDecorations()
        }
    }

    private fun loadImages() {
        if (isGeneratedViewer) {
            images.clear()
            if (isRemoteGenerated) {
                val originals = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS").orEmpty()
                val thumbnails = intent.getStringArrayListExtra("VIRTUAL_ALBUM_THUMBNAIL_URIS").orEmpty()
                originals.forEachIndexed { index, original ->
                    images.add(
                        ImageEntry(
                            uri = Uri.parse(original),
                            thumbnailUri = thumbnails.getOrNull(index)?.let(Uri::parse)
                        )
                    )
                }
            } else {
                val folderUriStr = intent.getStringExtra("FOLDER_URI") ?: return
                val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUriStr)) ?: return
                folder.listFiles()
                    .filter { it.isFile && (it.type?.startsWith("image/") == true ||
                        it.name?.lowercase()?.let { name -> name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") } == true) }
                    .sortedByDescending { it.name }
                    .forEach { images.add(ImageEntry(it.uri)) }
            }
        } else {
            val currentSet = DataManager.imageSetList.find { it.name == albumName }
            if (currentSet != null) {
                val baseList = currentSet.filterImages(DataManager.allImages)
                images.clear()
                images.addAll(if (isSortAscending) baseList else baseList.reversed())
            }
            // 現在壁紙にしている画像がこのセットにあれば、列の一番最初に持ってくる
            DataManager.pinCurrentWallpaperFirst(this, images)
        }
    }

    override fun onResume() {
        super.onResume()
        loadImages()
        updateActiveImageHighlight()
        imageAdapter.notifyDataSetChanged()
    }

    override fun onBackPressed() {
        if (imageAdapter.isSelectionMode) {
            imageAdapter.stopSelectionMode()
        } else if (albumName.startsWith("生成:")) {
            closeGeneratedAlbum()
        } else {
            super.onBackPressed()
        }
    }

    private fun forgetVirtualUri(uriStr: String) {
        val uris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS") ?: return
        val thumbnails = intent.getStringArrayListExtra("VIRTUAL_ALBUM_THUMBNAIL_URIS")
        val index = uris.indexOf(uriStr)
        if (index != -1) {
            uris.removeAt(index)
            thumbnails?.let {
                if (index < it.size) it.removeAt(index)
            }
            intent.putStringArrayListExtra("VIRTUAL_ALBUM_URIS", uris)
            thumbnails?.let { intent.putStringArrayListExtra("VIRTUAL_ALBUM_THUMBNAIL_URIS", it) }
        }
    }

    private fun closeGeneratedAlbum() {
        finish()
        if (intent.getBooleanExtra("FROM_GENERATED_FOLDER_PICKER", false)) {
            overridePendingTransition(0, 0)
        }
    }
}
