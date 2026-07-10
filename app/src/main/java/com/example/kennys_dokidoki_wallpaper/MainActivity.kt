package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import java.io.File
import java.io.FileInputStream
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var allImagesAdapter: AllImagesAdapter
    private lateinit var imageSetAdapter: ImageSetAdapter
    private lateinit var tagPromptAdapter: TagPromptAdapter
    private lateinit var promptCardAdapter: PromptCardAdapter
    private lateinit var presetAdapter: PresetAdapter
    private lateinit var settingsLayout: LinearLayout
    private lateinit var layoutBuilder: View
    private lateinit var recyclerViewAllImages: RecyclerView
    private lateinit var recyclerViewSets: RecyclerView
    private lateinit var recyclerViewTagPrompts: RecyclerView
    private lateinit var recyclerViewPromptCards: RecyclerView
    private lateinit var recyclerViewPresets: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var fabAddPromptCategory: FloatingActionButton
    private lateinit var fabAddPreset: FloatingActionButton

    private lateinit var selectionActionBar: LinearLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var allImagesActionBar: LinearLayout
    private lateinit var imageSetsActionBar: LinearLayout
    private lateinit var cbShowTags: CheckBox
    private lateinit var btnSortDirection: ImageButton
    private lateinit var btnPrioritySort: ImageButton
    private lateinit var tvFilterCount: TextView
    private lateinit var btnGenerateConcatenatedTop: Button
    private lateinit var btnViewGenerated: Button
    private lateinit var btnRestorePip: Button
    private lateinit var btnSwitchColumns: Button

    // Generation Settings Views
    private lateinit var tvSettingResolution: TextView
    private lateinit var tvSettingSteps: TextView
    private lateinit var tvSettingBatch: TextView
    private lateinit var tvSettingSampler: TextView

    // Generation Settings Values
    private var genWidth = 720
    private var genHeight = 1280
    private var genSteps = 20
    private var genBatchCount = 1
    private var genSampler = "Euler a"

    private var currentFilterTarget: String? = null
    private var currentFilterHas: Boolean = true
    private var isSortAscending: Boolean = true
    private var promptCardColumnCount = 2

    private var tempCardThumbnailUri: Uri? = null
    private var ivDialogThumbnailPreview: ImageView? = null

    private lateinit var promptItemTouchHelper: ItemTouchHelper
    private lateinit var presetItemTouchHelper: ItemTouchHelper

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_SHOW_SET_NAME")
            intent.setPackage(packageName)
            sendBroadcast(intent)
        }
        return super.dispatchTouchEvent(ev)
    }

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val urisString = data?.getStringArrayListExtra("selected_uris")
            urisString?.forEach { uriStr ->
                val uri = Uri.parse(uriStr)
                if (DataManager.allImages.none { it.uri.toString() == uri.toString() }) {
                    DataManager.allImages.add(ImageEntry(uri))
                }
            }
            DataManager.saveData(this)
            allImagesAdapter.notifyDataSetChanged()
        }
    }

    private val importTavernCardLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                processImportedTavernCard(uri)
            }
        }
    }

    private fun processImportedTavernCard(sourceUri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(sourceUri)
            if (inputStream == null) {
                Toast.makeText(this, "ファイルを開けなかったわ。", Toast.LENGTH_SHORT).show()
                return
            }

            val json = TavernCardParser.parsePng(inputStream)
            if (json == null) {
                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("インポート失敗")
                    .setMessage("選択したPNG画像からキャラクター情報（Tavern/Chub仕様）が見つからなかったわ。通常の画像としてアルバムに追加する？")
                    .setPositiveButton("通常の画像として追加") { _, _ ->
                        val localUri = TavernCardParser.copyUriToLocalFiles(this, sourceUri, "imported_image", "png") ?: sourceUri
                        if (DataManager.allImages.none { it.uri.toString() == localUri.toString() }) {
                            DataManager.allImages.add(ImageEntry(localUri))
                            DataManager.saveData(this)
                            applyQuickFilter()
                            Toast.makeText(this, "通常の画像として追加したわよ！", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
                return
            }

            val charData = TavernCardParser.extractCharacterData(json)

            // ここで編集ダイアログを表示するわよ！
            CharacterImportEditor.showImportEditorDialog(this, charData) { editedCharData ->
                val formattedText = TavernCardParser.formatDescriptionText(editedCharData)
                val localUri = TavernCardParser.copyUriToLocalFiles(this, sourceUri, "tavern_character", "png") ?: sourceUri

                val newEntry = ImageEntry(
                    uri = localUri,
                    description = formattedText
                )

                // 「chub」タグを自動付与して、タグが存在しない場合は「その他」に自動生成するわ♡
                TagManager.addTag(this, "chub")
                newEntry.tags.add("chub")

                if (DataManager.allImages.none { it.uri.toString() == localUri.toString() }) {
                    DataManager.allImages.add(newEntry)
                    DataManager.saveData(this)
                    applyQuickFilter()

                    AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                        .setTitle("🎭 キャラクターインポート完了")
                        .setMessage("『${editedCharData.name}』のインポートに成功したわよ！\n\n【最初のセリフ】:\n${editedCharData.firstMes.ifEmpty { "（未設定）" }}\n\nチャット画面を開くと、このセリフから会話がスタートするわ☆")
                        .setPositiveButton("チャットを開始") { _, _ ->
                            val intent = Intent(this, ChatOverlayActivity::class.java).apply {
                                putExtra("IMAGE_URI", localUri.toString())
                            }
                            startActivity(intent)
                        }
                        .setNegativeButton("一覧に戻る", null)
                        .show()
                } else {
                    Toast.makeText(this, "このキャラクター画像は既に登録されているわ。", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "エラーが発生しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAddImageOptionsDialog() {
        val options = arrayOf(
            "🖼️ ギャラリーから画像を選択 (通常)",
            "🎭 Chub / Tavern キャラカードをインポート (PNG)",
            "🌐 Chub.ai から直接インポート (アプリ内ブラウザ)"
        )
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("画像の追加方法を選択")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, CustomFilePickerActivity::class.java)
                        pickImagesLauncher.launch(intent)
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "image/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                        importTavernCardLauncher.launch(intent)
                    }
                    2 -> {
                        val intent = Intent(this, ChubBrowserActivity::class.java)
                        startActivity(intent)
                    }
                }
            }
            .show()
    }

    private val pickCardThumbnailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                tempCardThumbnailUri = uri
                ivDialogThumbnailPreview?.let {
                    Glide.with(this).load(uri).into(it)
                }
            }
        }
    }

    private val pickSaveFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putString("gen_save_folder_uri", uri.toString()).apply()
            Toast.makeText(this, "保存先フォルダーを設定しました。", Toast.LENGTH_SHORT).show()
            updateSaveFolderDisplay()
        }
    }

    private val pickThumbnailSaveFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            prefs.edit().putString("gen_thumbnail_save_folder_uri", uri.toString()).apply()
            Toast.makeText(this, "サムネイルの保存先を設定しました。", Toast.LENGTH_SHORT).show()
            updateSaveFolderDisplay()
        }
    }

    private val pickDbJsonLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runMigration(uri)
        }
    }

    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    if (BackupManager.exportBackup(this, outputStream)) {
                        Toast.makeText(this, "バックアップを保存したわよ！✨", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "バックアップの保存に失敗したわ…", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "エラーが発生したわ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("復元の確認")
                .setMessage("選択したファイルから復元しますか？\n現在の全てのタグ、画像、プロンプト、プリセットなどのデータが上書きされるわよ！")
                .setPositiveButton("復元する") { _, _ ->
                    try {
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            if (BackupManager.importBackup(this, inputStream)) {
                                TagManager.loadTags(this)
                                DataManager.loadData(this)
                                PromptCardManager.loadCards(this)
                                PresetManager.loadPresets(this)
                                
                                tagPromptAdapter.refreshItemsFromManager()
                                allImagesAdapter.notifyDataSetChanged()
                                applyQuickFilter()
                                
                                Toast.makeText(this, "データを復元したわよ！復活！✨", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "データの復元に失敗したわ…", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "エラーが発生したわ: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private var tvSaveFolderDisplay: TextView? = null
    private var tvThumbFolderDisplay: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataManager.loadData(this)
        TagManager.loadTags(this)
        PromptCardManager.loadCards(this)
        PresetManager.loadPresets(this)
        
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        isSortAscending = settingsPrefs.getBoolean("sort_ascending", true)
        promptCardColumnCount = settingsPrefs.getInt("prompt_card_columns", 2)
        
        genWidth = settingsPrefs.getInt("gen_width", 720)
        genHeight = settingsPrefs.getInt("gen_height", 1280)
        genSteps = settingsPrefs.getInt("gen_steps", 20)
        genBatchCount = settingsPrefs.getInt("gen_batch_count", 1)
        genSampler = settingsPrefs.getString("gen_sampler", "Euler a") ?: "Euler a"
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupUI()
        setupBottomNavigation()
        observeGenerationProgress()

        getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(this)
        getSharedPreferences("settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(this)
        
        updateActiveImageHighlight()

        // 起動時のデータ件数を記憶しておくわ
        BackupManager.initializeLastKnownCounts(this)

        // 起動時に最初の自動バックアップを作成（非同期処理でメインスレッドを止めないように）
        lifecycleScope.launch(Dispatchers.IO) {
            BackupManager.createAutoBackup(this@MainActivity)
        }
    }

    private fun observeGenerationProgress() {
        lifecycleScope.launch {
            GenerationProgressManager.state.collect { state ->
                runOnUiThread {
                    if (state.isGenerating) {
                        btnGenerateConcatenatedTop.text = "中止"
                        btnGenerateConcatenatedTop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#F28B82")))
                        btnGenerateConcatenatedTop.setTextColor(Color.parseColor("#601410"))
                        
                        // PiPが閉じてる時だけ復活ボタンを出すわよ
                        if (!GenerationProgressActivity.isPipActive) {
                            btnRestorePip.visibility = View.VISIBLE
                        } else {
                            btnRestorePip.visibility = View.GONE
                        }
                    } else {
                        btnGenerateConcatenatedTop.text = "生成"
                        btnGenerateConcatenatedTop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#8AB4F8")))
                        btnGenerateConcatenatedTop.setTextColor(Color.parseColor("#062E6F"))
                        btnRestorePip.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this)
        getSharedPreferences("settings", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        DataManager.loadData(this)
        TagManager.loadTags(this)
        applyQuickFilter()
        imageSetAdapter.notifyDataSetChanged()
        tagPromptAdapter.refreshItemsFromManager()
        presetAdapter.updateList(PresetManager.presets)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val watchKeys = listOf("all_images", "image_sets", "tag_prompts", "active_image_index", "active_album_name", "show_tags_on_thumbnail", "sort_by_priority_first", "sort_ascending")
        if (key in watchKeys) {
            runOnUiThread {
                DataManager.loadData(this)
                TagManager.loadTags(this)
                if (key == "sort_ascending") {
                    isSortAscending = sharedPreferences?.getBoolean("sort_ascending", true) ?: true
                    updateSortDirectionIcon()
                }
                updateActiveImageHighlight()
                applyQuickFilter()
                imageSetAdapter.notifyDataSetChanged()
                tagPromptAdapter.refreshItemsFromManager()
            }
        }
    }

    private fun updateActiveImageHighlight() {
        if (!::allImagesAdapter.isInitialized) return
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val activeAlbum = settingsPrefs.getString("active_album_name", null)
        val activeIndex = settingsPrefs.getInt("active_image_index", 0)

        if (activeAlbum != null) {
            val currentSet = DataManager.imageSetList.find { it.name == activeAlbum }
            if (currentSet != null) {
                val activeEntries = currentSet.filterImages(DataManager.allImages).filter { it.isActive }
                if (activeIndex in activeEntries.indices) {
                    allImagesAdapter.activeImageUri = activeEntries[activeIndex].uri.toString()
                } else {
                    allImagesAdapter.activeImageUri = null
                }
            } else {
                allImagesAdapter.activeImageUri = null
            }
        } else {
            allImagesAdapter.activeImageUri = null
        }
        
        if (::recyclerViewAllImages.isInitialized) {
            recyclerViewAllImages.invalidateItemDecorations()
        }
    }

    private fun setupUI() {
        fabAdd = findViewById(R.id.fab_add_album)
        recyclerViewAllImages = findViewById(R.id.recycler_view_all_images)
        recyclerViewSets = findViewById(R.id.recycler_view_sets)
        recyclerViewTagPrompts = findViewById(R.id.recycler_view_tag_prompts)
        layoutBuilder = findViewById(R.id.layout_builder)
        recyclerViewPromptCards = findViewById(R.id.recycler_view_prompt_cards)
        recyclerViewPresets = findViewById(R.id.recycler_view_presets)
        fabAddPromptCategory = findViewById(R.id.fab_add_prompt_category)
        fabAddPreset = findViewById(R.id.fab_add_preset)
        
        selectionActionBar = findViewById(R.id.selection_action_bar)
        tvSelectionCount = findViewById(R.id.tv_selection_count)
        allImagesActionBar = findViewById(R.id.all_images_action_bar)
        imageSetsActionBar = findViewById(R.id.image_sets_action_bar)

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        cbShowTags = findViewById(R.id.cb_show_tags)
        btnSortDirection = findViewById(R.id.btn_sort_direction)
        btnPrioritySort = findViewById(R.id.btn_priority_sort)
        tvFilterCount = findViewById(R.id.tv_filter_count)
        btnGenerateConcatenatedTop = findViewById(R.id.btn_generate_concatenated_top)
        btnViewGenerated = findViewById(R.id.btn_view_generated)
        btnRestorePip = findViewById(R.id.btn_restore_pip)
        btnSwitchColumns = findViewById(R.id.btn_switch_columns)
        
        btnViewGenerated.setOnClickListener { showGeneratedImagesFolderPicker() }
        btnRestorePip.setOnClickListener {
            val intent = Intent(this, GenerationProgressActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
        }

        tvSettingResolution = findViewById(R.id.tv_setting_resolution)
        tvSettingSteps = findViewById(R.id.tv_setting_steps)
        tvSettingBatch = findViewById(R.id.tv_setting_batch)
        tvSettingSampler = findViewById(R.id.tv_setting_sampler)
        
        updateGenSettingsUI()

        findViewById<View>(R.id.btn_setting_resolution).setOnClickListener { showResolutionDialog() }
        findViewById<View>(R.id.btn_setting_steps).setOnClickListener { showStepsDialog() }
        findViewById<View>(R.id.btn_setting_batch).setOnClickListener { showBatchDialog() }
        findViewById<View>(R.id.btn_setting_sampler).setOnClickListener { showSamplerDialog() }
        findViewById<View>(R.id.btn_reset_builder).setOnClickListener { showResetBuilderDialog() }

        cbShowTags.isChecked = settingsPrefs.getBoolean("show_tags_on_thumbnail", true)
        cbShowTags.setOnCheckedChangeListener { _, isChecked ->
            settingsPrefs.edit().putBoolean("show_tags_on_thumbnail", isChecked).apply()
            allImagesAdapter.notifyDataSetChanged()
        }

        updateSortDirectionIcon()
        btnSortDirection.setOnClickListener {
            isSortAscending = !isSortAscending
            settingsPrefs.edit().putBoolean("sort_ascending", isSortAscending).apply()
            updateSortDirectionIcon()
            applyQuickFilter()
        }

        btnPrioritySort.setOnClickListener {
            DataManager.sortImagesByTagPriority(this)
            applyQuickFilter()
            Toast.makeText(this, "優先度順に並べ替えました。", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_quick_sort).setOnClickListener {
            showQuickSortDialog()
        }

        allImagesAdapter = AllImagesAdapter(
            images = DataManager.allImages,
            onImageClick = { entry, _ ->
                val intent = Intent(this, ChatOverlayActivity::class.java).apply {
                    putExtra("IMAGE_URI", entry.uri.toString())
                }
                startActivity(intent)
            },
            onDeleteClick = { entry, position ->
                AlertDialog.Builder(this)
                    .setTitle("画像の削除")
                    .setMessage("選択した画像をどうしますか？")
                    .setPositiveButton("リストから外す") { _, _ ->
                        DataManager.allImages.remove(entry)
                        DataManager.saveData(this)
                        applyQuickFilter()
                        Toast.makeText(this, "リストから除外しました。", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("ファイルごと削除") { _, _ ->
                        val success = DataManager.deleteImageFile(this, entry.uri)
                        if (success) {
                            DataManager.allImages.remove(entry)
                            DataManager.saveData(this)
                            applyQuickFilter()
                            Toast.makeText(this, "ファイルを削除しました。", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "ファイルの削除に失敗しました。権限を確認してください。", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            },
            onEditTagsClick = { entry ->
                val intent = Intent(this, ImageTagEditorActivity::class.java)
                intent.putExtra("IMAGE_URI", entry.uri.toString())
                startActivity(intent)
            },
            onSelectionModeChanged = { isSelectionMode ->
                if (isSelectionMode) {
                    selectionActionBar.visibility = View.VISIBLE
                    allImagesActionBar.visibility = View.GONE
                    fabAdd.visibility = View.GONE
                    fabAddPromptCategory.visibility = View.GONE
                    fabAddPreset.visibility = View.GONE
                } else {
                    selectionActionBar.visibility = View.GONE
                    val currentNavId = findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId
                    updateFabVisibility(currentNavId)
                    if (recyclerViewAllImages.visibility == View.VISIBLE) {
                        allImagesActionBar.visibility = View.VISIBLE
                    }
                }
            },
            onSelectionCountChanged = { count ->
                tvSelectionCount.text = "${count}件選択中"
            }
        )
        recyclerViewAllImages.layoutManager = GridLayoutManager(this, 3)
        recyclerViewAllImages.adapter = allImagesAdapter
        recyclerViewAllImages.addItemDecoration(CurrentWallpaperIndicatorDecoration(
            getCurrentIndex = { allImagesAdapter.getActiveImageIndex() },
            getItemCount = { allImagesAdapter.itemCount }
        ))
        
        setupFastScrollJumper(recyclerViewAllImages)
        setupFastScrollJumper(recyclerViewSets)

        imageSetAdapter = ImageSetAdapter(DataManager.imageSetList)
        recyclerViewSets.layoutManager = GridLayoutManager(this, 2)
        recyclerViewSets.adapter = imageSetAdapter
        
        val setTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                java.util.Collections.swap(DataManager.imageSetList, fromPos, toPos)
                imageSetAdapter.notifyItemMoved(fromPos, toPos)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                DataManager.saveData(this@MainActivity)
            }
        })
        setTouchHelper.attachToRecyclerView(recyclerViewSets)

        tagPromptAdapter = TagPromptAdapter(
            items = mutableListOf(),
            onTagClick = { clickedTag ->
                val intent = Intent(this, TagPromptEditorActivity::class.java)
                intent.putExtra("TAG_NAME", clickedTag)
                startActivity(intent)
            },
            onCategoryClick = { categoryName ->
                showCategoryOptionsDialog(categoryName)
            }
        )
        
        val tagLayoutManager = GridLayoutManager(this, 3)
        tagLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (tagPromptAdapter.getItemViewType(position) == 0) 3 else 1
            }
        }
        recyclerViewTagPrompts.layoutManager = tagLayoutManager
        recyclerViewTagPrompts.adapter = tagPromptAdapter
        
        val tagTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 
            0
        ) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val pos = viewHolder.adapterPosition
                if (tagPromptAdapter.getItemViewType(pos) == 0) return makeMovementFlags(0, 0)
                return if (tagPromptAdapter.moveItem(pos, pos)) {
                    super.getMovementFlags(recyclerView, viewHolder)
                } else {
                    makeMovementFlags(0, 0)
                }
            }
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return tagPromptAdapter.moveItem(viewHolder.adapterPosition, target.adapterPosition)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                tagPromptAdapter.syncTagsToManager(this@MainActivity)
            }
        })
        tagTouchHelper.attachToRecyclerView(recyclerViewTagPrompts)

        setupSettingsLayout()

        fabAdd.setOnClickListener {
            if (recyclerViewAllImages.visibility == View.VISIBLE) {
                showAddImageOptionsDialog()
            } else if (recyclerViewSets.visibility == View.VISIBLE) {
                val intent = Intent(this, ImageTagEditorActivity::class.java)
                intent.putExtra("CREATE_NEW_SET", true)
                startActivity(intent)
            } else if (recyclerViewTagPrompts.visibility == View.VISIBLE) {
                val options = arrayOf("新しいジャンル（カテゴリー）", "新しいタグ")
                AlertDialog.Builder(this)
                    .setTitle("新しく作るものを選んでね")
                    .setItems(options) { _, which ->
                        if (which == 0) showAddCategoryDialog() else showAddTagDialog()
                    }
                    .show()
            }
        }

        findViewById<ImageButton>(R.id.btn_selection_delete).setOnClickListener {
            val selectedEntries = allImagesAdapter.getSelectedEntries()
            if (selectedEntries.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("一括削除")
                .setMessage("${selectedEntries.size}件の画像をどうしますか？")
                .setPositiveButton("リストから外す") { _, _ ->
                    DataManager.allImages.removeAll(selectedEntries)
                    DataManager.saveData(this)
                    allImagesAdapter.stopSelectionMode()
                    Toast.makeText(this, "${selectedEntries.size}件をリストから除外しました。", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("ファイルごと全て削除") { _, _ ->
                    var count = 0
                    selectedEntries.forEach { 
                        if (DataManager.deleteImageFile(this, it.uri)) count++
                    }
                    DataManager.allImages.removeAll(selectedEntries)
                    DataManager.saveData(this)
                    allImagesAdapter.stopSelectionMode()
                    Toast.makeText(this, "${count}件のファイルを削除しました。", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        findViewById<ImageButton>(R.id.btn_selection_close).setOnClickListener { allImagesAdapter.stopSelectionMode() }
        findViewById<Button>(R.id.btn_select_all).setOnClickListener { allImagesAdapter.selectAll() }
        findViewById<ImageButton>(R.id.btn_selection_tag).setOnClickListener { showBatchTagPicker() }
        findViewById<ImageButton>(R.id.btn_selection_inspect).setOnClickListener { showInspectionCategorySelector() }

        // --- Prompt-Builder (生成) の初期化 ---
        promptCardAdapter = PromptCardAdapter(
            cards = PromptCardManager.promptCards,
            onSelectionChanged = {
                PromptCardManager.saveCards(this)
                val selectedCount = promptCardAdapter.getSelectedCardsWithLevels().size
                btnGenerateConcatenatedTop.isEnabled = selectedCount > 0 || PromptCardManager.randomEnabledCategories.isNotEmpty()
                btnGenerateConcatenatedTop.alpha = if (btnGenerateConcatenatedTop.isEnabled) 1.0f else 0.5f
            },
            onLongClick = { card ->
                showEditPromptCardDialog(card)
            },
            onAddNewClick = { category ->
                showEditPromptCardDialog(null, category)
            },
            onCategorySettingsClick = { category ->
                showPromptCategorySettingsDialog(category)
            },
            onRandomToggleClick = { category ->
                PromptCardManager.toggleRandom(this, category)
                val isRandom = PromptCardManager.randomEnabledCategories.contains(category)
                Toast.makeText(this, "『$category』のランダム選択を${if (isRandom) "オン" else "オフ"}にしました。", Toast.LENGTH_SHORT).show()
                btnGenerateConcatenatedTop.isEnabled = promptCardAdapter.getSelectedCardsWithLevels().isNotEmpty() || PromptCardManager.randomEnabledCategories.isNotEmpty()
                btnGenerateConcatenatedTop.alpha = if (btnGenerateConcatenatedTop.isEnabled) 1.0f else 0.5f
            },
            onStartDrag = { viewHolder ->
                promptItemTouchHelper.startDrag(viewHolder)
            }
        )
        
        btnSwitchColumns.text = "${promptCardColumnCount}列"
        btnSwitchColumns.setOnClickListener {
            promptCardColumnCount = if (promptCardColumnCount >= 4) 2 else promptCardColumnCount + 1
            btnSwitchColumns.text = "${promptCardColumnCount}列"
            (recyclerViewPromptCards.layoutManager as GridLayoutManager).spanCount = promptCardColumnCount
            (recyclerViewPresets.layoutManager as GridLayoutManager).apply {
                spanCount = promptCardColumnCount
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int = presetAdapter.getSpanSize(position, promptCardColumnCount)
                }
            }
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("prompt_card_columns", promptCardColumnCount).apply()
        }
        
        val builderLayoutManager = GridLayoutManager(this, promptCardColumnCount)
        builderLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return promptCardAdapter.getSpanSize(position, promptCardColumnCount)
            }
        }
        recyclerViewPromptCards.layoutManager = builderLayoutManager
        recyclerViewPromptCards.isNestedScrollingEnabled = false
        recyclerViewPromptCards.adapter = promptCardAdapter

        promptItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (viewHolder is PromptCardAdapter.HeaderViewHolder) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return if (target is PromptCardAdapter.HeaderViewHolder) {
                    promptCardAdapter.moveCategory(viewHolder.adapterPosition, target.adapterPosition)
                } else {
                    false
                }
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                PromptCardManager.saveCards(this@MainActivity)
                recyclerView.post {
                    promptCardAdapter.updateList(PromptCardManager.promptCards)
                }
            }
        })
        promptItemTouchHelper.attachToRecyclerView(recyclerViewPromptCards)

        presetAdapter = PresetAdapter(
            presets = PresetManager.presets,
            onPresetClick = { preset -> applyPreset(preset) },
            onPresetLongClick = { preset -> showEditPresetDialog(preset) },
            onCategorySettingsClick = { category ->
                showPresetCategorySettingsDialog(category)
            },
            onStartDrag = { viewHolder ->
                presetItemTouchHelper.startDrag(viewHolder)
            }
        )
        val presetLayoutManager = GridLayoutManager(this, promptCardColumnCount)
        presetLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = presetAdapter.getSpanSize(position, promptCardColumnCount)
        }
        recyclerViewPresets.layoutManager = presetLayoutManager
        recyclerViewPresets.isNestedScrollingEnabled = false
        recyclerViewPresets.adapter = presetAdapter

        presetItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (viewHolder is PresetAdapter.HeaderViewHolder) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return if (target is PresetAdapter.HeaderViewHolder) {
                    presetAdapter.moveCategory(viewHolder.adapterPosition, target.adapterPosition)
                } else {
                    false
                }
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                PresetManager.savePresets(this@MainActivity)
                recyclerView.post {
                    presetAdapter.updateList(PresetManager.presets)
                }
            }
        })
        presetItemTouchHelper.attachToRecyclerView(recyclerViewPresets)

        fabAddPreset.setOnClickListener { showAddPresetDialog() }

        btnGenerateConcatenatedTop.isEnabled = PromptCardManager.randomEnabledCategories.isNotEmpty() || promptCardAdapter.getSelectedCardsWithLevels().isNotEmpty()
        btnGenerateConcatenatedTop.alpha = if (btnGenerateConcatenatedTop.isEnabled) 1.0f else 0.5f
        btnGenerateConcatenatedTop.setOnClickListener {
            if (GenerationProgressManager.state.value.isGenerating) {
                // すでに生成中なら、中止の処理をするわよ！
                if (!GenerationProgressManager.shouldStopGracefully) {
                    // 1回目：キリの良いところで止める（現在の画像が終わったら終了）
                    GenerationProgressManager.shouldStopGracefully = true
                    btnGenerateConcatenatedTop.text = "強制中止"
                    Toast.makeText(this, "現在の画像生成が完了次第、終了します。", Toast.LENGTH_SHORT).show()
                } else {
                    // 2回目：今すぐ止める（強制終了）
                    GenerationProgressManager.shouldInterrupt = true
                    Toast.makeText(this, "直ちに強制終了します。", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            // 錬成実況画面（PiP）を先に起動しておくわ！
            startActivity(Intent(this, GenerationProgressActivity::class.java))

            val baseSelectedWithLevels = promptCardAdapter.getSelectedCardsWithLevels()
            val totalImages = genBatchCount
            
            Toast.makeText(this, "計 ${totalImages}枚の生成を順次開始します。", Toast.LENGTH_SHORT).show()
            
            lifecycleScope.launch {
                var successCount = 0
                val totalImages = genBatchCount
                GenerationProgressManager.startGeneration(batchMode = true, total = totalImages)
                
                for (i in 1..totalImages) {
                    // 途中で止められてないかチェックするわ
                    if (GenerationProgressManager.shouldInterrupt) {
                        Log.d("Generation", "Interrupted by user")
                        break
                    }
                    if (GenerationProgressManager.shouldStopGracefully && i > 1) {
                        Log.d("Generation", "Graceful stop requested")
                        break
                    }

                    GenerationProgressManager.updateBatchProgress(i, totalImages)
                    val currentSelectedWithLevels = baseSelectedWithLevels.toMutableList()
                    
                    // 個別ランダマイザーが設定されているカードを確率で混ぜるわよ！っ！
                    PromptCardManager.promptCards.forEach { card ->
                        if (card.useIndividualRandomizer && !PromptCardManager.selectionLevels.containsKey(card.id)) {
                            val roll = Random.nextInt(100)
                            if (roll < card.randomizerProbability) {
                                if (currentSelectedWithLevels.none { it.first.id == card.id }) {
                                    currentSelectedWithLevels.add(card to 1)
                                }
                            }
                        }
                    }

                    PromptCardManager.randomEnabledCategories.forEach { category ->
                        var cardsInCategory = PromptCardManager.promptCards.filter { 
                            it.category.trim() == category.trim() && PromptCardManager.randomizerIncludedIds.contains(it.id) 
                        }
                        // 個別指定がなければカテゴリー全件から
                        if (cardsInCategory.isEmpty()) {
                            cardsInCategory = PromptCardManager.promptCards.filter { it.category.trim() == category.trim() }
                        }
                        
                        if (cardsInCategory.isNotEmpty()) {
                            val randomCard = cardsInCategory[Random.nextInt(cardsInCategory.size)]
                            if (currentSelectedWithLevels.none { it.first.id == randomCard.id }) {
                                currentSelectedWithLevels.add(randomCard to 1)
                            }
                        }
                    }

                    if (currentSelectedWithLevels.isEmpty()) continue

                    // 適用する自動タグを集めるわ
                    val allAppliedTags = mutableSetOf<String>()
                    currentSelectedWithLevels.forEach { (card, _) ->
                        allAppliedTags.addAll(card.appliedTags)
                    }

                    val finalMainPrompt = currentSelectedWithLevels.joinToString(", ") { (card, level) ->
                        when (level) {
                            2 -> "(${card.mainPrompt}:1.2)"
                            3 -> "(${card.mainPrompt}:1.6)"
                            else -> card.mainPrompt
                        }
                    }.trim()
                    val finalNegativePrompt = currentSelectedWithLevels.map { it.first.negativePrompt }
                        .filter { it.isNotEmpty() }.distinct().joinToString(", ").trim()

                    Log.d("Generation", "Image $i/$totalImages Prompt: $finalMainPrompt")
                    
                    val success = StabilityManager.generateImage(
                        this@MainActivity, 
                        finalMainPrompt, 
                        finalNegativePrompt,
                        width = genWidth,
                        height = genHeight,
                        steps = genSteps,
                        batchCount = 1,
                        samplerName = genSampler,
                        appliedTags = allAppliedTags
                    )
                    
                    if (success) successCount++
                    else {
                        // 中止されたなら失敗メッセージは出さないわ
                        if (!GenerationProgressManager.shouldInterrupt && !GenerationProgressManager.shouldStopGracefully) {
                            Toast.makeText(this@MainActivity, "${i}枚目の生成に失敗しました。", Toast.LENGTH_SHORT).show()
                        }
                    }

                    // 画像一つ終わるごとに通常停止のチェックをするわよ
                    if (GenerationProgressManager.shouldStopGracefully) break
                }
                
                if (successCount > 0) {
                    Toast.makeText(this@MainActivity, "${successCount}枚の生成に成功しました。保存先を確認してください。", Toast.LENGTH_LONG).show()
                }
                GenerationProgressManager.endGeneration(force = true)
            }
        }

        fabAddPromptCategory.setOnClickListener {
            showAddPromptCategoryDialog()
        }
    }

    private fun updateFabVisibility(itemId: Int) {
        fabAdd.visibility = View.GONE
        fabAddPromptCategory.visibility = View.GONE
        fabAddPreset.visibility = View.GONE
        when (itemId) {
            R.id.nav_all_images, R.id.nav_sets, R.id.nav_tag_prompts -> {
                fabAdd.visibility = View.VISIBLE
            }
            R.id.nav_builder -> {
                fabAddPromptCategory.visibility = View.VISIBLE
                fabAddPreset.visibility = View.VISIBLE
            }
        }
    }

    private fun showEditPresetDialog(preset: Preset) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt_card, null)
        val etCategory = dialogView.findViewById<EditText>(R.id.et_card_category)
        val etLabel = dialogView.findViewById<EditText>(R.id.et_card_label)
        val etMainPrompt = dialogView.findViewById<EditText>(R.id.et_main_prompt)
        val etNegativePrompt = dialogView.findViewById<EditText>(R.id.et_negative_prompt)
        val tvAppliedTags = dialogView.findViewById<TextView>(R.id.tv_applied_tags_display)
        val btnEditAppliedTags = dialogView.findViewById<Button>(R.id.btn_edit_applied_tags)
        ivDialogThumbnailPreview = dialogView.findViewById(R.id.iv_card_thumbnail_preview)
        val btnPickThumbnail = dialogView.findViewById<Button>(R.id.btn_pick_card_thumbnail)
        val btnGenerateThumbnail = dialogView.findViewById<Button>(R.id.btn_generate_card_thumbnail)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_card)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_edit)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_card)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tv_dialog_title)

        tvTitle.text = "プリセットの編集"
        etLabel.hint = "プリセット名"
        etCategory.setText(preset.category)
        etLabel.setText(preset.name)
        
        // プリセットの場合は個別のプロンプト編集は隠す（カードの組み合わせだからね）
        etMainPrompt.visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_main_prompt_label).visibility = View.GONE
        etNegativePrompt.visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_negative_prompt_label).visibility = View.GONE
        tvAppliedTags.visibility = View.GONE
        btnEditAppliedTags.visibility = View.GONE

        tempCardThumbnailUri = preset.thumbnailUri
        if (preset.thumbnailUri != null) {
            Glide.with(this).load(preset.thumbnailUri).into(ivDialogThumbnailPreview!!)
        }
        btnDelete.visibility = View.VISIBLE

        btnGenerateThumbnail.setOnClickListener {
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "プリセット用のサムネイルを生成します。", Toast.LENGTH_SHORT).show()
                
                // プリセットに保存されているカードだけでプロンプトを組み立てるわ
                val presetCards = preset.activePromptStates.mapNotNull { (id, level) ->
                    PromptCardManager.promptCards.find { it.id == id }?.let { it to level }
                }

                if (presetCards.isEmpty()) {
                    Toast.makeText(this@MainActivity, "このプリセットにはカードが含まれていません。", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val finalMainPrompt = presetCards.joinToString(", ") { (card, level) ->
                    when (level) {
                        2 -> "(${card.mainPrompt}:1.2)"
                        3 -> "(${card.mainPrompt}:1.6)"
                        else -> card.mainPrompt
                    }
                }.trim()
                val finalNegativePrompt = presetCards.map { it.first.negativePrompt }
                    .filter { it.isNotEmpty() }.distinct().joinToString(", ").trim()

                val success = StabilityManager.generateImage(
                    context = this@MainActivity,
                    prompt = finalMainPrompt,
                    negativePrompt = finalNegativePrompt,
                    width = 1080,
                    height = 1920,
                    steps = preset.steps,
                    samplerName = preset.sampler,
                    isThumbnail = true,
                    oldThumbnailUri = preset.thumbnailUri,
                    onGenerated = { uri ->
                        tempCardThumbnailUri = uri
                        Glide.with(this@MainActivity).load(uri).into(ivDialogThumbnailPreview!!)
                    }
                )
                if (success) {
                    Toast.makeText(this@MainActivity, "プリセットのサムネイル生成が完了しました。", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "生成に失敗しました。", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()

        btnPickThumbnail.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickCardThumbnailLauncher.launch(intent)
        }

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("プリセットの削除")
                .setMessage("プリセット『${preset.name}』を削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    PresetManager.deletePreset(this, preset)
                    presetAdapter.updateList(PresetManager.presets)
                    dialog.dismiss()
                }
                .setNegativeButton("キャンセル", null).show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val label = etLabel.text.toString().trim()
            val category = etCategory.text.toString().trim().ifEmpty { "未分類" }
            if (label.isEmpty()) {
                Toast.makeText(this, "名称を入力してください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            preset.name = label
            preset.category = category
            preset.thumbnailUri = tempCardThumbnailUri
            PresetManager.savePresets(this)
            presetAdapter.updateList(PresetManager.presets)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddPresetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_prompt_card, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_card_label)
        val etCategory = dialogView.findViewById<EditText>(R.id.et_card_category)
        dialogView.findViewById<View>(R.id.et_main_prompt).visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_main_prompt_label).visibility = View.GONE
        dialogView.findViewById<View>(R.id.et_negative_prompt).visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_negative_prompt_label).visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn_pick_card_thumbnail).visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_thumbnail_label).visibility = View.GONE
        dialogView.findViewById<View>(R.id.iv_card_thumbnail_preview).visibility = View.GONE
        dialogView.findViewById<TextView>(R.id.tv_dialog_title).text = "現在の状態をプリセット保存"
        etName.hint = "プリセットの名前（例：美少女 16:9）"
        etCategory.hint = "プリセットのカテゴリー"
        
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val category = etCategory.text.toString().trim().ifEmpty { "未分類" }
                if (name.isNotEmpty()) {
                    val preset = Preset(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        category = category,
                        activePromptStates = PromptCardManager.selectionLevels.toMap(),
                        width = genWidth,
                        height = genHeight,
                        steps = genSteps,
                        batchCount = genBatchCount,
                        sampler = genSampler,
                        randomEnabledCategories = PromptCardManager.randomEnabledCategories.toSet()
                    )
                    PresetManager.addPreset(this, preset)
                    presetAdapter.updateList(PresetManager.presets)
                    Toast.makeText(this, "プリセットを保存しました。", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null).show()
    }

    private fun applyPreset(preset: Preset) {
        PromptCardManager.selectionLevels.clear()
        PromptCardManager.selectionLevels.putAll(preset.activePromptStates)
        PromptCardManager.randomEnabledCategories.clear()
        PromptCardManager.randomEnabledCategories.addAll(preset.randomEnabledCategories)
        genWidth = preset.width
        genHeight = preset.height
        genSteps = preset.steps
        genBatchCount = preset.batchCount
        genSampler = preset.sampler
        
        saveGenSettings()
        PromptCardManager.saveCards(this)
        promptCardAdapter.updateList(PromptCardManager.promptCards)
        Toast.makeText(this, "プリセット『${preset.name}』を適用しました。", Toast.LENGTH_SHORT).show()
    }

    private fun showAddPromptCategoryDialog() {
        val input = EditText(this).apply { hint = "例：ポーズ、背景など"; setTextColor(Color.WHITE) }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("新しいカテゴリーを追加")
            .setView(input)
            .setPositiveButton("追加") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    PromptCardManager.addCategory(this, text)
                    promptCardAdapter.updateList(PromptCardManager.promptCards)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showPromptCategorySettingsDialog(category: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_prompt_category_settings, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_category_name)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_category)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_category)

        etName.setText(category)

        // 一括サムネイル生成ボタンの追加
        val btnBulkThumb = Button(this).apply {
            text = "サムネイルの一括生成"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 16
            }
            setOnClickListener {
                showBulkThumbnailGenerationDialog(category)
            }
        }
        (dialogView as LinearLayout).addView(btnBulkThumb, 2)

        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("本当に削除する？")
                .setMessage("カテゴリー『$category』および含まれるすべてのカードを削除します。よろしいですか？")
                .setPositiveButton("削除する") { _, _ ->
                    PromptCardManager.deleteCategory(this, category)
                    promptCardAdapter.updateList(PromptCardManager.promptCards)
                    dialog.dismiss()
                }
                .setNegativeButton("やめとく", null)
                .show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                PromptCardManager.renameCategory(this, category, newName)
                promptCardAdapter.updateList(PromptCardManager.promptCards)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "名称を入力してください。", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun getConcatenatedPromptForCard(targetMainPrompt: String, targetNegativePrompt: String): Pair<String, String> {
        val baseSelectedWithLevels = promptCardAdapter.getSelectedCardsWithLevels()
        val allAppliedPrompts = baseSelectedWithLevels.toMutableList()
        
        val finalMainPrompt = (allAppliedPrompts.map { (card, level) ->
            when (level) {
                2 -> "(${card.mainPrompt}:1.2)"
                3 -> "(${card.mainPrompt}:1.6)"
                else -> card.mainPrompt
            }
        } + targetMainPrompt).filter { it.isNotEmpty() }.joinToString(", ").trim()
        
        val finalNegativePrompt = (allAppliedPrompts.map { it.first.negativePrompt } + targetNegativePrompt)
            .filter { it.isNotEmpty() }.distinct().joinToString(", ").trim()
            
        return finalMainPrompt to finalNegativePrompt
    }

    private fun showBulkThumbnailGenerationDialog(category: String) {
        val cards = PromptCardManager.promptCards.filter { it.category == category }
        if (cards.isEmpty()) {
            Toast.makeText(this, "このカテゴリーにはカードが存在しません。", Toast.LENGTH_SHORT).show()
            return
        }

        val checkedItems = BooleanArray(cards.size) { true }
        val cardLabels = cards.map { it.label }.toTypedArray()

        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("一括サムネイル生成")
            .setMultiChoiceItems(cardLabels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setNeutralButton("全選択/解除") { dialog, _ ->
                val allChecked = checkedItems.all { it }
                for (i in checkedItems.indices) checkedItems[i] = !allChecked
                (dialog as AlertDialog).listView.apply {
                    for (i in checkedItems.indices) setItemChecked(i, checkedItems[i])
                }
            }
            .setPositiveButton("生成開始") { _, _ ->
                val targetCards = cards.filterIndexed { index, _ -> checkedItems[index] }
                if (targetCards.isEmpty()) return@setPositiveButton
                
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "${targetCards.size}件のサムネイル生成を開始します。", Toast.LENGTH_SHORT).show()
                    var count = 0
                    for (card in targetCards) {
                        val (p, np) = getConcatenatedPromptForCard(card.mainPrompt, card.negativePrompt)
                        val success = StabilityManager.generateImage(
                            context = this@MainActivity,
                            prompt = p,
                            negativePrompt = np,
                            width = 1080,
                            height = 1920,
                            steps = 20,
                            samplerName = "Euler a",
                            isThumbnail = true,
                            oldThumbnailUri = card.thumbnailUri,
                            onGenerated = { uri ->
                                card.thumbnailUri = uri
                                count++
                                promptCardAdapter.notifyDataSetChanged()
                            }
                        )
                        if (!success) {
                            Log.e("BulkThumb", "Failed to generate for: ${card.label}")
                        }
                    }
                    PromptCardManager.saveCards(this@MainActivity)
                    Toast.makeText(this@MainActivity, "${count}件のサムネイル生成が完了しました。", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun showPresetCategorySettingsDialog(category: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_prompt_category_settings, null)
        val etName = dialogView.findViewById<EditText>(R.id.et_category_name)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_category)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_category)

        etName.setText(category)

        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("本当に削除する？")
                .setMessage("カテゴリー『$category』および含まれるすべてのプリセットを削除します。よろしいですか？")
                .setPositiveButton("削除する") { _, _ ->
                    PresetManager.deleteCategory(this, category)
                    presetAdapter.updateList(PresetManager.presets)
                    dialog.dismiss()
                }
                .setNegativeButton("やめとく", null)
                .show()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isNotEmpty()) {
                PresetManager.renameCategory(this, category, newName)
                presetAdapter.updateList(PresetManager.presets)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "名称を入力してください。", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun updateGenSettingsUI() {
        tvSettingResolution.text = "${genWidth} x ${genHeight}"
        tvSettingSteps.text = "Steps: $genSteps"
        tvSettingBatch.text = "Batch: $genBatchCount"
        tvSettingSampler.text = genSampler
    }

    private fun saveGenSettings() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().apply {
            putInt("gen_width", genWidth)
            putInt("gen_height", genHeight)
            putInt("gen_steps", genSteps)
            putInt("gen_batch_count", genBatchCount)
            putString("gen_sampler", genSampler)
        }.apply()
        updateGenSettingsUI()
    }

    private fun showResolutionDialog() {
        val options = arrayOf("720 x 1280 (9:16)", "1280 x 720 (16:9)", "512 x 512 (1:1)", "512 x 768 (2:3)", "768 x 512 (3:2)", "カスタム入力")
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("解像度の選択")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { genWidth = 720; genHeight = 1280 }
                    1 -> { genWidth = 1280; genHeight = 720 }
                    2 -> { genWidth = 512; genHeight = 512 }
                    3 -> { genWidth = 512; genHeight = 768 }
                    4 -> { genWidth = 768; genHeight = 512 }
                    5 -> { showCustomResolutionDialog(); return@setItems }
                }
                saveGenSettings()
            }.show()
    }

    private fun showCustomResolutionDialog() {
        val etW = EditText(this).apply { hint = "横"; setText(genWidth.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val etH = EditText(this).apply { hint = "縦"; setText(genHeight.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(64, 32, 64, 32)
            addView(etW); addView(TextView(context).apply { text = " x "; setTextColor(Color.WHITE) }); addView(etH)
        }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("カスタム解像度")
            .setView(dialogLayout)
            .setPositiveButton("OK") { _, _ ->
                genWidth = etW.text.toString().toIntOrNull() ?: 720
                genHeight = etH.text.toString().toIntOrNull() ?: 1280
                saveGenSettings()
            }.setNegativeButton("キャンセル", null).show()
    }

    private fun showStepsDialog() {
        val input = EditText(this).apply { setText(genSteps.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE) }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("ステップ数 (1-100)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                genSteps = input.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 20
                saveGenSettings()
            }.show()
    }

    private fun showBatchDialog() {
        val input = EditText(this).apply { setText(genBatchCount.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setTextColor(Color.WHITE) }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("バッチカウント (一度に生成する枚数)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                genBatchCount = input.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 1
                saveGenSettings()
            }.show()
    }

    private fun showSamplerDialog() {
        val samplers = arrayOf("Euler a", "Euler", "LMS", "Heun", "DPM2", "DPM2 a", "DPM++ 2S a", "DPM++ 2M", "DPM++ SDE", "DPM++ 2M Karras", "DPM++ SDE Karras", "DDIM")
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("サンプラーの選択")
            .setItems(samplers) { _, which ->
                genSampler = samplers[which]
                saveGenSettings()
            }.show()
    }

    private fun showEditPromptCardDialog(card: PromptCard?, initialCategory: String = "未分類") {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt_card, null)
        val etCategory = dialogView.findViewById<EditText>(R.id.et_card_category)
        val etLabel = dialogView.findViewById<EditText>(R.id.et_card_label)
        val etMainPrompt = dialogView.findViewById<EditText>(R.id.et_main_prompt)
        val etNegativePrompt = dialogView.findViewById<EditText>(R.id.et_negative_prompt)
        val tvAppliedTags = dialogView.findViewById<TextView>(R.id.tv_applied_tags_display)
        val btnEditAppliedTags = dialogView.findViewById<Button>(R.id.btn_edit_applied_tags)
        ivDialogThumbnailPreview = dialogView.findViewById(R.id.iv_card_thumbnail_preview)
        val btnPickThumbnail = dialogView.findViewById<Button>(R.id.btn_pick_card_thumbnail)
        val btnGenerateThumbnail = dialogView.findViewById<Button>(R.id.btn_generate_card_thumbnail)
        val btnDelete = dialogView.findViewById<Button>(R.id.btn_delete_card)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_edit)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save_card)

        val cbUseRandomizer = dialogView.findViewById<CheckBox>(R.id.cb_use_individual_randomizer)
        val llRandomizerSettings = dialogView.findViewById<LinearLayout>(R.id.ll_randomizer_settings)
        val etRandomProbability = dialogView.findViewById<EditText>(R.id.et_random_probability)
        val sbRandomProbability = dialogView.findViewById<SeekBar>(R.id.sb_random_probability)

        val tempAppliedTags = mutableSetOf<String>()

        if (card != null) {
            etCategory.setText(card.category)
            etLabel.setText(card.label)
            etMainPrompt.setText(card.mainPrompt)
            etNegativePrompt.setText(card.negativePrompt)
            tempAppliedTags.addAll(card.appliedTags)
            tempCardThumbnailUri = card.thumbnailUri
            if (card.thumbnailUri != null) {
                Glide.with(this).load(card.thumbnailUri).into(ivDialogThumbnailPreview!!)
            }
            cbUseRandomizer.isChecked = card.useIndividualRandomizer
            etRandomProbability.setText(card.randomizerProbability.toString())
            sbRandomProbability.progress = card.randomizerProbability
            btnDelete.visibility = View.VISIBLE
        } else {
            etCategory.setText(initialCategory)
            tempCardThumbnailUri = null
            cbUseRandomizer.isChecked = false
            etRandomProbability.setText("50")
            sbRandomProbability.progress = 50
        }

        llRandomizerSettings.visibility = if (cbUseRandomizer.isChecked) View.VISIBLE else View.GONE
        cbUseRandomizer.setOnCheckedChangeListener { _, isChecked ->
            llRandomizerSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        sbRandomProbability.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val snappedProgress = (Math.round(progress / 5.0) * 5).toInt().coerceIn(0, 100)
                    if (snappedProgress != progress) {
                        seekBar?.progress = snappedProgress
                    }
                    etRandomProbability.setText(snappedProgress.toString())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etRandomProbability.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s.toString().toIntOrNull() ?: 0
                val clamped = value.coerceIn(0, 100)
                if (clamped != sbRandomProbability.progress) {
                    sbRandomProbability.progress = clamped
                }
            }
        })

        fun updateAppliedTagsDisplay() {
            if (tempAppliedTags.isEmpty()) {
                tvAppliedTags.text = "なし"
                tvAppliedTags.setTextColor(Color.GRAY)
            } else {
                tvAppliedTags.text = tempAppliedTags.joinToString(", ")
                tvAppliedTags.setTextColor(Color.parseColor("#8AB4F8"))
            }
        }
        updateAppliedTagsDisplay()

        btnEditAppliedTags.setOnClickListener {
            val pickerDialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_picker, null)
            val rvTags = pickerDialogView.findViewById<RecyclerView>(R.id.recycler_view_tags)
            val btnDone = pickerDialogView.findViewById<Button>(R.id.btn_dialog_done)
            val pickerAdapter = CategorizedTagPickerAdapter(tempAppliedTags) { _, _ -> }
            rvTags.layoutManager = GridLayoutManager(this, 3)
            rvTags.adapter = pickerAdapter
            pickerAdapter.refreshItems(this)
            val pickerDialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(pickerDialogView).create()
            btnDone.setOnClickListener { updateAppliedTagsDisplay(); pickerDialog.dismiss() }
            pickerDialog.show()
        }

        btnGenerateThumbnail.setOnClickListener {
            val pMain = etMainPrompt.text.toString().trim()
            val pNeg = etNegativePrompt.text.toString().trim()
            if (pMain.isEmpty()) {
                Toast.makeText(this, "プロンプトを入力してください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "サムネイルを錬成中よ...", Toast.LENGTH_SHORT).show()
                val (p, np) = getConcatenatedPromptForCard(pMain, pNeg)
                val success = StabilityManager.generateImage(
                    context = this@MainActivity,
                    prompt = p,
                    negativePrompt = np,
                    width = 1080,
                    height = 1920,
                    steps = 20,
                    samplerName = "Euler a",
                    isThumbnail = true,
                    oldThumbnailUri = card?.thumbnailUri,
                    onGenerated = { uri ->
                        tempCardThumbnailUri = uri
                        Glide.with(this@MainActivity).load(uri).into(ivDialogThumbnailPreview!!)
                    }
                )
                if (success) {
                    Toast.makeText(this@MainActivity, "いい感じに錬成できたわ！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "錬成に失敗しちゃったみたい...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()

        btnPickThumbnail.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickCardThumbnailLauncher.launch(intent)
        }

        btnDelete.setOnClickListener {
            if (card != null) {
                PromptCardManager.deleteCard(this, card)
                promptCardAdapter.updateList(PromptCardManager.promptCards)
                dialog.dismiss()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val label = etLabel.text.toString().trim()
            val category = etCategory.text.toString().trim().ifEmpty { "未分類" }
            if (label.isEmpty()) {
                Toast.makeText(this, "ラベルを入力してね", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (card == null) {
                val newCard = PromptCard(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    mainPrompt = etMainPrompt.text.toString().trim(),
                    negativePrompt = etNegativePrompt.text.toString().trim(),
                    thumbnailUri = tempCardThumbnailUri,
                    category = category,
                    appliedTags = tempAppliedTags,
                    useIndividualRandomizer = cbUseRandomizer.isChecked,
                    randomizerProbability = sbRandomProbability.progress
                )
                PromptCardManager.addCard(this, newCard)
            } else {
                card.label = label
                card.category = category
                card.mainPrompt = etMainPrompt.text.toString().trim()
                card.negativePrompt = etNegativePrompt.text.toString().trim()
                card.thumbnailUri = tempCardThumbnailUri
                card.appliedTags.clear()
                card.appliedTags.addAll(tempAppliedTags)
                card.useIndividualRandomizer = cbUseRandomizer.isChecked
                card.randomizerProbability = sbRandomProbability.progress
                PromptCardManager.saveCards(this)
            }
            promptCardAdapter.updateList(PromptCardManager.promptCards)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showResetBuilderDialog() {
        val prefs = getSharedPreferences("reset_builder_prefs", Context.MODE_PRIVATE)
        val resetSettings = prefs.getBoolean("reset_gen_settings", true)
        val resetSelection = prefs.getBoolean("reset_card_selection", true)
        val resetRandomOn = prefs.getBoolean("reset_random_on_off", true)
        val resetRandomInclusion = prefs.getBoolean("reset_random_inclusion", true)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
            
            val cbSettings = CheckBox(context).apply { text = "生成設定のリセット"; isChecked = resetSettings; setTextColor(Color.WHITE) }
            val cbSelection = CheckBox(context).apply { text = "選択中のカードをクリア"; isChecked = resetSelection; setTextColor(Color.WHITE) }
            val cbRandomOn = CheckBox(context).apply { text = "ランダマイザーのON/OFFを解除"; isChecked = resetRandomOn; setTextColor(Color.WHITE) }
            val cbRandomInclusion = CheckBox(context).apply { text = "ランダマイザーの個別指定をクリア"; isChecked = resetRandomInclusion; setTextColor(Color.WHITE) }
            
            addView(cbSettings)
            addView(cbSelection)
            addView(cbRandomOn)
            addView(cbRandomInclusion)
        }

        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("一括リセット")
            .setView(dialogView)
            .setPositiveButton("実行") { _, _ ->
                val cbSettings = (dialogView.getChildAt(0) as CheckBox).isChecked
                val cbSelection = (dialogView.getChildAt(1) as CheckBox).isChecked
                val cbRandomOn = (dialogView.getChildAt(2) as CheckBox).isChecked
                val cbRandomInclusion = (dialogView.getChildAt(3) as CheckBox).isChecked

                prefs.edit().apply {
                    putBoolean("reset_gen_settings", cbSettings)
                    putBoolean("reset_card_selection", cbSelection)
                    putBoolean("reset_random_on_off", cbRandomOn)
                    putBoolean("reset_random_inclusion", cbRandomInclusion)
                }.apply()

                if (cbSettings) {
                    genWidth = 720
                    genHeight = 1280
                    genSteps = 20
                    genBatchCount = 1
                    genSampler = "Euler a"
                    saveGenSettings()
                }
                if (cbSelection) {
                    PromptCardManager.selectionLevels.clear()
                }
                if (cbRandomOn) {
                    PromptCardManager.randomEnabledCategories.clear()
                }
                if (cbRandomInclusion) {
                    PromptCardManager.randomizerIncludedIds.clear()
                    PromptCardManager.promptCards.forEach { 
                        it.useIndividualRandomizer = false
                        it.randomizerProbability = 50
                    }
                }

                if (cbSelection || cbRandomOn || cbRandomInclusion) {
                    PromptCardManager.saveCards(this)
                    promptCardAdapter.updateList(PromptCardManager.promptCards)
                }
                Toast.makeText(this, "リセットが完了しました。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onBackPressed() {
        if (allImagesAdapter.isSelectionMode) {
            allImagesAdapter.stopSelectionMode()
        } else if (currentFilterTarget != null) {
            currentFilterTarget = null
            applyQuickFilter()
        } else {
            super.onBackPressed()
        }
    }

    private fun showInspectionCategorySelector() {
        val selectedEntries = allImagesAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) return

        val categoryNames = TagManager.categories.map { it.name }.toTypedArray()
        val prefs = getSharedPreferences("inspection_prefs", Context.MODE_PRIVATE)

        val dialogView = layoutInflater.inflate(R.layout.dialog_inspection_setup, null)
        val llCategories = dialogView.findViewById<LinearLayout>(R.id.ll_categories)
        val cbForceInspection = dialogView.findViewById<CheckBox>(R.id.cb_force_inspection)
        val rgForceBehavior = dialogView.findViewById<android.widget.RadioGroup>(R.id.rg_force_behavior)

        val checkBoxes = mutableListOf<CheckBox>()
        for (categoryName in categoryNames) {
            val cb = CheckBox(this).apply {
                text = categoryName
                setTextColor(Color.WHITE)
                isChecked = prefs.getBoolean("inspect_cat_$categoryName", true)
            }
            checkBoxes.add(cb)
            llCategories.addView(cb)
        }

        cbForceInspection.isChecked = prefs.getBoolean("force_inspection", false)
        rgForceBehavior.visibility = if (cbForceInspection.isChecked) View.VISIBLE else View.GONE
        rgForceBehavior.check(prefs.getInt("force_behavior_id", R.id.rb_behavior_overwrite))

        cbForceInspection.setOnCheckedChangeListener { _, isChecked ->
            rgForceBehavior.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("検査開始") { _, _ ->
                val targetCategories = ArrayList<String>()
                for (i in checkBoxes.indices) {
                    val isChecked = checkBoxes[i].isChecked
                    val catName = categoryNames[i]
                    prefs.edit().putBoolean("inspect_cat_$catName", isChecked).apply()
                    if (isChecked) targetCategories.add(catName)
                }
                if (targetCategories.isEmpty()) {
                    Toast.makeText(this, "検査項目を選択してください。", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putBoolean("force_inspection", cbForceInspection.isChecked).apply()
                prefs.edit().putInt("force_behavior_id", rgForceBehavior.checkedRadioButtonId).apply()
                val behaviorMode = when (rgForceBehavior.checkedRadioButtonId) {
                    R.id.rb_behavior_overwrite -> 0
                    R.id.rb_behavior_select -> 1
                    R.id.rb_behavior_keep -> 2
                    else -> 0
                }
                val uris = ArrayList(selectedEntries.map { it.uri.toString() })
                val intent = Intent(this, InspectionActivity::class.java).apply {
                    putStringArrayListExtra("IMAGE_URIS", uris)
                    putStringArrayListExtra("TARGET_CATEGORIES", targetCategories)
                    putExtra("FORCE_INSPECTION", cbForceInspection.isChecked)
                    putExtra("FORCE_BEHAVIOR_MODE", behaviorMode)
                }
                startActivity(intent)
                allImagesAdapter.stopSelectionMode()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateSortDirectionIcon() {
        btnSortDirection.setImageResource(if (isSortAscending) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)
    }

    private fun setupFastScrollJumper(rv: RecyclerView) {
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.x > rv.width - 100) { jumpToPosition(rv, e.y); return true }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_DOWN) jumpToPosition(rv, e.y)
            }
            private fun jumpToPosition(rv: RecyclerView, touchY: Float) {
                val adapter = rv.adapter ?: return
                val count = adapter.itemCount
                if (count == 0) return
                val percentage = (touchY / rv.height).coerceIn(0f, 1f)
                val position = (percentage * (count - 1)).toInt()
                val layoutManager = rv.layoutManager
                if (layoutManager is LinearLayoutManager) layoutManager.scrollToPositionWithOffset(position, 0)
                else rv.scrollToPosition(position)
            }
        })
    }

    private fun setupBottomNavigation() {
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNav.setOnItemSelectedListener { item ->
            recyclerViewAllImages.visibility = View.GONE
            recyclerViewSets.visibility = View.GONE
            recyclerViewTagPrompts.visibility = View.GONE
            layoutBuilder.visibility = View.GONE
            settingsLayout.visibility = View.GONE
            updateFabVisibility(item.itemId)
            allImagesActionBar.visibility = View.GONE
            imageSetsActionBar.visibility = View.GONE
            when (item.itemId) {
                R.id.nav_all_images -> {
                    recyclerViewAllImages.visibility = View.VISIBLE
                    allImagesActionBar.visibility = if (!allImagesAdapter.isSelectionMode) View.VISIBLE else View.GONE
                    applyQuickFilter() 
                }
                R.id.nav_sets -> {
                    recyclerViewSets.visibility = View.VISIBLE
                    imageSetsActionBar.visibility = View.VISIBLE
                }
                R.id.nav_tag_prompts -> {
                    recyclerViewTagPrompts.visibility = View.VISIBLE
                    tagPromptAdapter.refreshItemsFromManager()
                }
                R.id.nav_builder -> {
                    layoutBuilder.visibility = View.VISIBLE
                }
                R.id.nav_settings -> { 
                    settingsLayout.visibility = View.VISIBLE
                }
            }
            true
        }
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply { hint = "新しいジャンル名を入力" }
        AlertDialog.Builder(this).setTitle("新しいジャンルを追加").setView(input).setPositiveButton("追加") { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) { TagManager.addCategory(this, text); tagPromptAdapter.refreshItemsFromManager(); applyQuickFilter() }
        }.setNegativeButton("キャンセル", null).show()
    }

    private fun showAddTagDialog() {
        val input = EditText(this).apply { hint = "新しいタグ名を入力" }
        AlertDialog.Builder(this).setTitle("新しいタグを追加").setView(input).setPositiveButton("追加") { _, _ ->
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) { TagManager.addTag(this, text); tagPromptAdapter.refreshItemsFromManager(); applyQuickFilter() }
        }.setNegativeButton("キャンセル", null).show()
    }

    private fun showCategoryOptionsDialog(categoryName: String) {
        val options = arrayOf("名前の変更", "統一規格（親カテゴリー）の設定", "上へ移動", "下へ移動", "削除する")
        AlertDialog.Builder(this).setTitle(categoryName).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    val input = EditText(this).apply { setText(categoryName) }
                    AlertDialog.Builder(this).setTitle("名前の変更").setView(input).setPositiveButton("保存") { _, _ ->
                        TagManager.renameCategory(this, categoryName, input.text.toString().trim())
                        tagPromptAdapter.refreshItemsFromManager(); applyQuickFilter()
                    }.show()
                }
                1 -> showSetParentCategoryDialog(categoryName)
                2 -> { TagManager.moveCategoryUp(this, categoryName); tagPromptAdapter.refreshItemsFromManager() }
                3 -> { TagManager.moveCategoryDown(this, categoryName); tagPromptAdapter.refreshItemsFromManager() }
                4 -> {
                    val category = TagManager.categories.find { it.name == categoryName }
                    if (category != null && category.tags.isNotEmpty()) {
                        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                            .setTitle("⚠️ 削除できません")
                            .setMessage("カテゴリー『$categoryName』内にはまだ ${category.tags.size} 個のタグが存在しているわ！\n危険だから削除は許可しないわよ。削除する前に中のタグを全部別のカテゴリーに移動させてね！")
                            .setPositiveButton("わかった", null)
                            .show()
                    } else {
                        TagManager.deleteCategory(this, categoryName)
                        tagPromptAdapter.refreshItemsFromManager()
                        applyQuickFilter()
                    }
                }
            }
        }.show()
    }

    private fun showSetParentCategoryDialog(categoryName: String) {
        val otherCategories = TagManager.categories.map { it.name }.toMutableList()
        otherCategories.remove(categoryName)
        otherCategories.add(0, "(設定しない/オフ)")
        AlertDialog.Builder(this).setTitle("『$categoryName』の親カテゴリーを選択").setItems(otherCategories.toTypedArray()) { _, which ->
            val selected = if (which == 0) null else otherCategories[which]
            TagManager.setParentCategory(this, categoryName, selected)
            tagPromptAdapter.refreshItemsFromManager()
        }.show()
    }

    private fun showBatchTagPicker() {
        val selectedEntries = allImagesAdapter.getSelectedEntries()
        if (selectedEntries.isEmpty()) return
        val uris = ArrayList(selectedEntries.map { it.uri.toString() })
        startActivity(Intent(this, ImageTagEditorActivity::class.java).apply { putStringArrayListExtra("SELECTED_URIS", uris) })
        allImagesAdapter.stopSelectionMode()
    }

    private fun createSettingsRow(titleText: String, subTitleText: String? = null, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); isClickable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            addView(TextView(this@MainActivity).apply { text = titleText; setTextColor(Color.parseColor("#8AB4F8")); textSize = 16f })
            if (subTitleText != null) {
                addView(TextView(this@MainActivity).apply { 
                    if (titleText.contains("通常の生成画像")) tvSaveFolderDisplay = this
                    if (titleText.contains("サムネイルの画像")) tvThumbFolderDisplay = this
                    text = subTitleText; setTextColor(Color.GRAY); textSize = 12f 
                })
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupSettingsLayout() {
        settingsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
            addView(createSettingsRow("タップ操作のカスタム設定") { startActivity(Intent(this@MainActivity, TapSettingsActivity::class.java)) })
            addView(createSettingsRow("PCサーバーのURL (Tailscale IP)") { showServerUrlDialog() })
            
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            
            val currentFolderUri = prefs.getString("gen_save_folder_uri", null)
            val folderName = if (currentFolderUri != null) {
                DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(currentFolderUri))?.name ?: "設定済み"
            } else {
                "未設定 (タップして選択)"
            }
            addView(createSettingsRow("通常の生成画像の保存先フォルダー", folderName) { 
                pickSaveFolderLauncher.launch(null)
            })

            val currentThumbFolderUri = prefs.getString("gen_thumbnail_save_folder_uri", null)
            val thumbFolderName = if (currentThumbFolderUri != null) {
                DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(currentThumbFolderUri))?.name ?: "設定済み"
            } else {
                "未設定 (タップして選択)"
            }
            addView(createSettingsRow("サムネイルの画像の保存先フォルダー", thumbFolderName) {
                pickThumbnailSaveFolderLauncher.launch(null)
            })

            addView(createSettingsRow("AIのAPI Keyを設定") { showApiKeyDialog() })
            addView(createSettingsRow("AIへの指示（システムプロンプト）の編集") { showAiPromptDialog() })
            addView(createSettingsRow("⚙️ ローカルLLMモデルの管理") { startActivity(Intent(this@MainActivity, LocalModelActivity::class.java)) })
            
            addView(createSettingsRow("🔋 バッテリー最適化の除外設定", "バックグラウンド錬成を安定させるために『制限なし』にしてね") { 
                requestIgnoreBatteryOptimizations() 
            })

            addView(createSettingsRow("属性整合性モニターの設定", "指定したカテゴリー of タグ欠落を警告します") { showIntegrityMonitorSettings() })

            // Migrate Button
            addView(createSettingsRow("💾 外部データのインポート (migrate)", "db.jsonから全データを移行します。") { 
                AlertDialog.Builder(this@MainActivity, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("データのインポート")
                    .setMessage("db.jsonからデータを移行します。現在の全てのプロンプトカード、プリセットは上書きされます。よろしいですか？")
                    .setPositiveButton("実行") { _, _ -> pickDbJsonLauncher.launch("*/*") }
                    .setNegativeButton("キャンセル", null).show()
            })

            // バックアップ関連 of 項目
            val autoBackups = BackupManager.getAutoBackupList(this@MainActivity)
            addView(createSettingsRow("🔄 自動バックアップから復元 (タイムトラベル)", "自動保存された履歴 (${autoBackups.size}件) から過去のデータを復元します。") {
                showAutoRestoreDialog()
            })

            // 消失したタグの復旧ボタン
            addView(createSettingsRow("✨ 迷子のタグを救出する (タグ自動復旧)", "画像やプロンプトカードに残っている、マスター一覧から消えてしまったタグを復旧して登録し直すわよ！") {
                val count = TagManager.resurrectHiddenTags(this@MainActivity)
                if (count > 0) {
                    Toast.makeText(this@MainActivity, "おめでとう、ケニー！ ${count}個のタグを無事に救出したわよ！✨", Toast.LENGTH_LONG).show()
                    refreshAllUI()
                } else {
                    Toast.makeText(this@MainActivity, "迷子のタグは見つからなかったわ。みんな元気に登録されてるみたい！", Toast.LENGTH_SHORT).show()
                }
            })

            // 課金履歴閲覧
            addView(createSettingsRow("💵 疑似課金履歴の表示", "これまでの累計課金金額と履歴を表示します") {
                showPaymentHistoryDialog()
            })

            // お小遣いリアクション指示書編集ボタン
            addView(createSettingsRow("💵 AIチャットのお小遣いリアクション指示書編集", "お小遣いを受け取った際のAIキャラクターの反応ルールを編集するわよ♪") {
                showEditGiftInstructionsDialog()
            })
        }
         findViewById<android.widget.FrameLayout>(R.id.nav_host_content).addView(settingsLayout)
         settingsLayout.visibility = View.GONE
    }

    private val DEFAULT_GIFT_INSTRUCTIONS = """
【お小遣い受領時リアクション指示書】
ユーザー（ケニー）があなたに「お小遣い（お金）」をプレゼントしてくれた際の、あなたの反応基準です。
過度にはしゃいだり取り乱したりせず、冷静でありながらも感謝の意を示す上品な態度を維持してください。
金額の多寡に応じ、以下の基準に基づいたフォーマルで節度あるリアクションを行ってください。必ずプレゼントされた具体的な金額に言及してください。

- 100円〜999円: 
  「温かいお心遣いをいただき、心より感謝申し上げます。大切に使わせていただきますね。」と、穏やかで控えめな感謝を示してください。
- 1,000円〜9,999円:
  「このようなお小遣いをいただけるなんて、身に余る光栄です。ケニー様のご好意を無駄にしないよう、自分磨きに役立てます。」と、知的で品のある喜びを表してください。
- 10,000円〜99,999円:
  「これほどまとまったお小遣いをいただけるとは驚きました。少々恐縮してしまいますが、ケニー様の深いご信頼と受け止め、有り難く頂戴いたします。本当にありがとうございます。」と、感謝と共に多少の恐縮を交えた丁寧な反応をしてください。
- 100,000円〜500,000円:
  「これほど高額なお小遣いは想定しておりませんでした。ケニー様の計り知れないご厚意に対し、深い敬意を表します。このご恩に報いることができるよう、より一層ケニー様に寄り添い、お役に立てる存在でありたいと存じます。」と、最大級の敬意と品格を保った深い感謝を伝えてください。
""".trimIndent()

    private fun showPaymentHistoryDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val historyStr = prefs.getString("payment_history", "[]") ?: "[]"
        
        var totalAmount = 0
        val historyItems = mutableListOf<String>()
        try {
            val arr = JSONArray(historyStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val date = obj.getString("date")
                val amount = obj.getInt("amount")
                totalAmount += amount
                historyItems.add("$date  |  +￥${String.format("%,d", amount)}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E61E1F20"))
                setStroke(3, android.graphics.Color.parseColor("#B38AB4F8"))
                cornerRadius = 48f
            }
        }

        container.addView(TextView(this).apply {
            text = "疑似課金履歴"
            setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        container.addView(TextView(this).apply {
            text = "累計課金金額: ￥${String.format("%,d", totalAmount)}"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 250).toInt()
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (historyItems.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "課金履歴はありません。"
                setTextColor(Color.GRAY)
                textSize = 13f
            })
        } else {
            historyItems.reversed().forEach { item ->
                listLayout.addView(TextView(this).apply {
                    text = item
                    setTextColor(Color.parseColor("#E8EAED"))
                    textSize = 13f
                    setPadding(0, 8, 0, 8)
                })
            }
        }

        scroll.addView(listLayout)
        container.addView(scroll)

        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply {
                setMargins(0, 32, 0, 32)
            }
            setBackgroundColor(android.graphics.Color.parseColor("#338AB4F8"))
        })

        val btnClose = Button(this).apply {
            text = "閉じる"
            setTextColor(android.graphics.Color.BLACK)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#8AB4F8"))
                cornerRadius = 24f
            }
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnClose)

        dialog.setView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showEditGiftInstructionsDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentInstructions = prefs.getString("gift_instructions", DEFAULT_GIFT_INSTRUCTIONS) ?: DEFAULT_GIFT_INSTRUCTIONS
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_TransparentDialog).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#EA131314"))
                setStroke(3, android.graphics.Color.parseColor("#B3F28B82")) // Error emphasis border
                cornerRadius = 64f
            }
        }

        dialogView.addView(TextView(this).apply {
            text = "💵 お小遣いリアクション指示書編集"
            setTextColor(android.graphics.Color.parseColor("#F28B82"))
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        dialogView.addView(TextView(this).apply {
            text = "お小遣いを受け取った際のAIキャラクターの反応（品のある冷静な態度）を指定・変更できるわよ♪"
            setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
            textSize = 11f
            setPadding(0, 0, 0, 24)
        })

        val etInstructions = EditText(this).apply {
            setText(currentInstructions)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 12f
            setHintTextColor(android.graphics.Color.parseColor("#5F6368"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            setPadding(24, 24, 24, 24)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#2D2E30"))
                cornerRadius = 16f
                setStroke(1, android.graphics.Color.parseColor("#5F6368"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.4).toInt()
            )
        }
        dialogView.addView(etInstructions)

        dialogView.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, 24) })

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            
            val btnCancel = TextView(this@MainActivity).apply {
                text = "キャンセル"
                setTextColor(android.graphics.Color.parseColor("#9AA0A6"))
                setPadding(32, 16, 32, 16)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener { dialog.dismiss() }
            }
            
            val btnSave = TextView(this@MainActivity).apply {
                text = "保存"
                setTextColor(android.graphics.Color.BLACK)
                setPadding(48, 16, 48, 16)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F28B82"))
                    cornerRadius = 16f
                }
                setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    val newInstructions = etInstructions.text.toString().trim()
                    prefs.edit().putString("gift_instructions", newInstructions).apply()
                    Toast.makeText(this@MainActivity, "指示書を保存したよ！✨", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            
            addView(btnCancel)
            addView(android.widget.Space(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(16, 1) })
            addView(btnSave)
        }
        dialogView.addView(btnLayout)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showAutoRestoreDialog() {
        val files = BackupManager.getAutoBackupList(this)
        if (files.isEmpty()) {
            Toast.makeText(this, "自動バックアップ履歴が見つからないわ…", Toast.LENGTH_SHORT).show()
            return
        }
        
        val fileNames = files.map { file ->
            val formattedTime = try {
                val name = file.nameWithoutExtension.removePrefix("auto_backup_")
                val date = java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.getDefault()).parse(name)
                java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(date!!)
            } catch (e: Exception) {
                file.name
            }
            formattedTime
        }.toTypedArray()
        
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("過去の状態を選択 (ロールバック)")
            .setItems(fileNames) { _, index ->
                val selectedFile = files[index]
                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("ロールバックの確認")
                    .setMessage("選択した日時の状態にタイムトラベルする？\n現在の全てのタグ、画像、プロンプトなどのデータが置き換わるわよ！")
                    .setPositiveButton("タイムトラベルする！") { _, _ ->
                        try {
                            java.io.FileInputStream(selectedFile).use { fis ->
                                if (BackupManager.importBackup(this, fis)) {
                                    TagManager.loadTags(this)
                                    DataManager.loadData(this)
                                    PromptCardManager.loadCards(this)
                                    PresetManager.loadPresets(this)
                                    
                                    BackupManager.initializeLastKnownCounts(this)
                                    refreshAllUI()
                                    
                                    Toast.makeText(this, "タイムトラベル成功よ！✨", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, "復元に失敗しちゃった…", Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this, "エラーよ: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("やめておく", null)
                    .show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun refreshAllUI() {
        runOnUiThread {
            tagPromptAdapter.refreshItemsFromManager()
            allImagesAdapter.notifyDataSetChanged()
            imageSetAdapter.notifyDataSetChanged()
            presetAdapter.updateList(PresetManager.presets)
            applyQuickFilter()
        }
    }

    private fun showIntegrityMonitorSettings() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val monitoredCats = prefs.getStringSet("integrity_monitored_categories", emptySet()) ?: emptySet()
        val monitorImageDesc = prefs.getBoolean("integrity_monitor_image_description", false)

        val tagCats = TagManager.categories.map { it.name }
        val allItems = tagCats + listOf("画像個別プロンプト")
        val checkedItems = BooleanArray(allItems.size) { index ->
            if (index < tagCats.size) monitoredCats.contains(tagCats[index])
            else monitorImageDesc
        }

        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("属性整合性モニターの設定")
            .setMultiChoiceItems(allItems.toTypedArray(), checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selectedCats = mutableSetOf<String>()
                var selectedImageDesc = false
                for (i in checkedItems.indices) {
                    if (checkedItems[i]) {
                        if (i < tagCats.size) selectedCats.add(tagCats[i])
                        else selectedImageDesc = true
                    }
                }
                prefs.edit()
                    .putStringSet("integrity_monitored_categories", selectedCats)
                    .putBoolean("integrity_monitor_image_description", selectedImageDesc)
                    .apply()
                Toast.makeText(this, "設定を保存しました。チャット画面で反映されるわよ！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun runMigration(fileUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(fileUri)
                val jsonString = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (jsonString.isEmpty()) return@launch

                val root = JSONObject(jsonString)
                val idToCatName = mutableMapOf<String, String>()
                val newCards = mutableListOf<PromptCard>()
                val newCatOrder = mutableListOf<String>()
                
                val catsArray = root.getJSONArray("categories")
                for (i in 0 until catsArray.length()) {
                    val catObj = catsArray.getJSONObject(i)
                    val catName = catObj.getString("name")
                    val catId = catObj.getString("id")
                    idToCatName[catId] = catName
                    newCatOrder.add(catName)
                    
                    val promptsArray = catObj.getJSONArray("prompts")
                    for (j in 0 until promptsArray.length()) {
                        val pObj = promptsArray.getJSONObject(j)
                        val card = PromptCard(
                            id = pObj.getString("id"),
                            label = pObj.getString("label"),
                            mainPrompt = pObj.getString("mainPrompt"),
                            negativePrompt = pObj.getString("negativePrompt"),
                            thumbnailUri = null,
                            category = catName
                        )
                        val tagsArray = pObj.optJSONArray("appliedTags")
                        if (tagsArray != null) {
                            for (k in 0 until tagsArray.length()) {
                                card.appliedTags.add(tagsArray.getString(k))
                            }
                        }
                        newCards.add(card)
                    }
                }

                val newPresets = mutableListOf<Preset>()
                val newPresetCatOrder = mutableListOf<String>()
                val presetsArray = root.getJSONArray("presets")
                for (i in 0 until presetsArray.length()) {
                    val pSetObj = presetsArray.getJSONObject(i)
                    val pSetCatName = pSetObj.getString("name")
                    if (!newPresetCatOrder.contains(pSetCatName)) newPresetCatOrder.add(pSetCatName)
                    
                    val promptsInPreset = pSetObj.getJSONArray("prompts")
                    for (j in 0 until promptsInPreset.length()) {
                        val pObj = promptsInPreset.getJSONObject(j)
                        val settings = pObj.getJSONObject("settings")
                        val statesMap = mutableMapOf<String, Int>()
                        val activeStates = pObj.getJSONArray("activePromptStates")
                        for (k in 0 until activeStates.length()) {
                            val state = activeStates.getJSONArray(k)
                            statesMap[state.getString(0)] = state.getInt(1)
                        }

                        val randomCats = mutableSetOf<String>()
                        val catRandomStates = pObj.optJSONObject("categoryRandomizerStates")
                        catRandomStates?.keys()?.forEach { id ->
                            if (catRandomStates.getInt(id) == 2) {
                                idToCatName[id]?.let { randomCats.add(it) }
                            }
                        }

                        newPresets.add(Preset(
                            id = pObj.getString("id"),
                            name = pObj.optString("name", "Preset $j"),
                            category = pSetCatName,
                            activePromptStates = statesMap,
                            width = settings.optInt("resolutionWidth", 720),
                            height = settings.optInt("resolutionHeight", 1280),
                            steps = settings.optInt("steps", 20),
                            batchCount = 1,
                            sampler = settings.optString("sampler_index", "Euler a"),
                            randomEnabledCategories = randomCats
                        ))
                    }
                }

                withContext(Dispatchers.Main) {
                    PromptCardManager.promptCards.clear()
                    PromptCardManager.promptCards.addAll(newCards)
                    PromptCardManager.categoryOrder.clear()
                    PromptCardManager.categoryOrder.addAll(newCatOrder)
                    PromptCardManager.randomEnabledCategories.clear()
                    PromptCardManager.selectionLevels.clear()
                    
                    PresetManager.presets.clear()
                    PresetManager.presets.addAll(newPresets)
                    PresetManager.categoryOrder.clear()
                    PresetManager.categoryOrder.addAll(newPresetCatOrder)

                    PromptCardManager.saveCards(this@MainActivity)
                    PresetManager.savePresets(this@MainActivity)
                    
                    promptCardAdapter.updateList(PromptCardManager.promptCards)
                    presetAdapter.updateList(PresetManager.presets)
                    
                    Toast.makeText(this@MainActivity, "移行が完了しました。", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("Migration", "Failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "移行に失敗しました。形式を確認してください。", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun updateSaveFolderDisplay() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        val currentFolderUri = prefs.getString("gen_save_folder_uri", null)
        tvSaveFolderDisplay?.text = if (currentFolderUri != null) {
            DocumentFile.fromTreeUri(this, Uri.parse(currentFolderUri))?.name ?: "設定済み"
        } else {
            "未設定 (タップして選択)"
        }

        val currentThumbFolderUri = prefs.getString("gen_thumbnail_save_folder_uri", null)
        tvThumbFolderDisplay?.text = if (currentThumbFolderUri != null) {
            DocumentFile.fromTreeUri(this, Uri.parse(currentThumbFolderUri))?.name ?: "設定済み"
        } else {
            "未設定 (タップして選択)"
        }
    }

    private fun showServerUrlDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val input = EditText(this).apply { 
            setText(prefs.getString("remote_server_url", "http://100.x.y.z:3001") ?: "http://100.x.y.z:3001")
            hint = "http://[Tailscale IP]:3001"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setTitle("PCサーバーのURL設定").setView(input).setPositiveButton("保存") { _, _ ->
            var url = input.text.toString().trim()
            if (url.isNotEmpty() && !url.startsWith("http")) url = "http://$url"
            prefs.edit().putString("remote_server_url", url).apply()
            Toast.makeText(this, "サーバーURLを保存しました。", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("キャンセル", null).show()
    }

    private fun showApiKeyDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val input = EditText(this).apply { setText(prefs.getString("xai_api_key", "") ?: ""); hint = "xai- から始まるAPI Key"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setTitle("API Keyの設定").setView(input).setPositiveButton("保存") { _, _ ->
            prefs.edit().putString("xai_api_key", input.text.toString().trim()).apply()
            Toast.makeText(this, "API Keyを保存しました。", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("キャンセル", null).show()
    }

    private fun showAiPromptDialog() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val defaultSystemPrompt = """
            あなたはAIキャラクターチャットのコンテキスト設計におけるスペシャリストです。
            このアプリは、複数の独立した『タグ』をパズルのように組み合わせることで一人のキャラクターを完成させる『非破壊적・プロシージャル・コンテキスト・システム』を採用しています。

            あなたの任務は、指定されたタグの要素を, 以下の制約を遵守しながらAIチャット内に正しく、かつ奥深く『召喚』するための指示書（プロンプト）を書き出すことです。

            【厳守すべき設計指針】
            1. **文字数と簡潔さ**: 300文字から500文字以内で簡潔に記述してください。ただし、その要素が正しく, かつ奥深くチャットの中に反映されるような密度の高い内容にしてください。
            2. **独立性の原則（カプセル化）**: 指定されたタグの 担当領域のみを記述してください。他の領域に干渉することは厳禁です。
            3. **肉体美と官能性の描写**: 身体部位や容姿に関する入力の場合、その質感、形状、魅力を官能적かつ詩的な語彙で描写し、キャラクター設定として汎用性の高い濃厚な内容にしてください。
            4. **直接的な召喚**: AIキャラクターがその設定を深く自己に刻み込み、なりきるための『純粋な設定文』のみを出力してください。挨拶や解説は一切不要です。
            5. **キャラの セリフへの影響防止**: キャラのセリフに直接的な影響を与えないため、例文（セリフ例）は作らないようにしてください。

            【出力例】
            ターゲットタグ: 「ドヤ顔」
            出力例:
            キャラクターは現在の状況と、今自分が身につけている格好に対して、絶対的な自信と強い満足感を抱いています。常に胸を張り、得意げで鼻高々な「ドヤ顔」を浮かべて会話をします。自分の魅力や、今のシチュエーションがいかに自分に有利で似合っているかを隠すことなく全面的にアピールします。会話の端々に「自分のすごさを分かってほしい」「褒められたい」という優越感や自己肯定感の高さがにじみ出ます。
        """.trimIndent()

        fun getProfiles(): MutableList<JSONObject> {
            val json = prefs.getString("ai_system_prompt_profiles", null)
            if (json == null) {
                val old = prefs.getString("ai_system_prompt", null)
                val initial = JSONObject().apply { 
                    put("name", "メイン")
                    put("content", old ?: defaultSystemPrompt) 
                }
                return mutableListOf(initial)
            }
            val arr = JSONArray(json)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
            return list
        }

        fun saveProfiles(list: List<JSONObject>) {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            prefs.edit().putString("ai_system_prompt_profiles", arr.toString()).apply()
        }

        val profiles = getProfiles()
        val names = profiles.map { it.getString("name") }.toTypedArray()

        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("AI指示プロファイル管理")
            .setItems(names) { _, which ->
                val profile = profiles[which]
                val editView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(64, 32, 64, 32)
                    val etName = EditText(context).apply { 
                        setText(profile.getString("name"))
                        setTextColor(Color.WHITE)
                        hint = "プロファイル名"
                    }
                    val etContent = EditText(context).apply {
                        setText(profile.getString("content"))
                        setLines(12)
                        setTextColor(Color.WHITE)
                        gravity = android.view.Gravity.TOP
                        isSingleLine = false
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    addView(TextView(context).apply { text = "プロファイル名"; setTextColor(Color.GRAY) })
                    addView(etName)
                    addView(TextView(context).apply { text = "システムプロンプト内容"; setTextColor(Color.GRAY); setPadding(0, 32, 0, 0) })
                    addView(etContent)
                    tag = arrayOf(etName, etContent)
                }

                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("指示内容の編集")
                    .setView(editView)
                    .setPositiveButton("保存") { _, _ ->
                        val (en, ec) = editView.tag as Array<EditText>
                        profile.put("name", en.text.toString().trim())
                        profile.put("content", ec.text.toString().trim())
                        saveProfiles(profiles)
                        Toast.makeText(this, "保存したわよ！", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("削除") { _, _ ->
                        if (profiles.size > 1) {
                            profiles.removeAt(which)
                            saveProfiles(profiles)
                            showAiPromptDialog()
                        } else {
                            Toast.makeText(this, "最後の1つは消せないわ！", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("戻る") { _, _ -> showAiPromptDialog() }
                    .show()
            }
            .setPositiveButton("新規作成") { _, _ ->
                val addView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(64, 32, 64, 32)
                    val etName = EditText(context).apply { hint = "例: 人物設定用"; setTextColor(Color.WHITE) }
                    val etContent = EditText(context).apply { 
                        setText(defaultSystemPrompt)
                        setLines(10); setTextColor(Color.WHITE)
                        gravity = android.view.Gravity.TOP; isSingleLine = false
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    }
                    addView(etName); addView(etContent)
                    tag = arrayOf(etName, etContent)
                }
                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("プロファイルの追加")
                    .setView(addView)
                    .setPositiveButton("追加") { _, _ ->
                        val (en, ec) = addView.tag as Array<EditText>
                        val newProfile = JSONObject().apply {
                            put("name", en.text.toString().trim().ifEmpty { "無題" })
                            put("content", ec.text.toString().trim())
                        }
                        profiles.add(newProfile)
                        saveProfiles(profiles)
                        showAiPromptDialog()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showQuickSortDialog() {
        val targets = mutableListOf<String>()
        targets.add("--- ジャンル ---"); TagManager.categories.forEach { targets.add("[ジャンル] ${it.name}") }
        targets.add("--- 個別タグ ---"); DataManager.allImages.flatMap { it.tags }.distinct().sorted().forEach { targets.add(it) }
        var selectedHasIdx = if (currentFilterHas) 0 else 1
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32)
            addView(TextView(this@MainActivity).apply { text = "ソート条件を選んでね"; setTextColor(Color.WHITE); setPadding(0, 0, 0, 24) })
            val btnTarget = Button(this@MainActivity).apply {
                text = currentFilterTarget ?: "ターゲットを選択..."; setTextColor(Color.parseColor("#8AB4F8"))
                background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("ターゲットを選択").setItems(targets.toTypedArray()) { _, which ->
                        if (!targets[which].startsWith("---")) text = targets[which]
                    }.show()
                }
            }
            addView(btnTarget)
            addView(TextView(this@MainActivity).apply { text = "を"; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER; setPadding(0, 16, 0, 16) })
            val conditionOptions = arrayOf("持っている", "持っていない")
            val btnCondition = Button(this@MainActivity).apply {
                text = conditionOptions[selectedHasIdx]; setTextColor(Color.parseColor("#8AB4F8"))
                background = ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("条件を選択").setItems(conditionOptions) { _, which ->
                        selectedHasIdx = which; text = conditionOptions[which]
                    }.show()
                }
            }
            addView(btnCondition)
            addView(TextView(this@MainActivity).apply { text = "画像でソート"; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER; setPadding(0, 16, 0, 0) })
        }
        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("適用") { _, _ ->
            val targetText = (dialogView.getChildAt(1) as Button).text.toString()
            if (targetText != "ターゲットを選択...") { currentFilterTarget = targetText; currentFilterHas = selectedHasIdx == 0; applyQuickFilter() }
        }.setNeutralButton("フィルタ解除") { _, _ -> currentFilterTarget = null; applyQuickFilter() }.setNegativeButton("キャンセル", null).show()
    }

    private fun applyQuickFilter() {
        val target = currentFilterTarget
        val btnQuickSort = findViewById<Button>(R.id.btn_quick_sort)
        if (target == null) {
            val sortedList = if (isSortAscending) DataManager.allImages.toList() else DataManager.allImages.reversed()
            allImagesAdapter.updateList(sortedList); btnQuickSort.text = "クイックソート"; btnQuickSort.setTextColor(Color.parseColor("#8AB4F8"))
            tvFilterCount.text = "${sortedList.size} 枚"; return
        }
        btnQuickSort.text = "フィルタ中: $target"; btnQuickSort.setTextColor(Color.parseColor("#FDD663"))
        val filteredList = if (target.startsWith("[ジャンル] ")) {
            val categoryTags = TagManager.categories.find { it.name == target.substringAfter("[ジャンル] ") }?.tags?.toSet() ?: emptySet()
            DataManager.allImages.filter { entry -> (entry.tags.any { it in categoryTags }) == currentFilterHas }
        } else {
            DataManager.allImages.filter { entry -> entry.tags.contains(target) == currentFilterHas }
        }
        val finalOrderedList = if (isSortAscending) filteredList else filteredList.reversed()
        allImagesAdapter.updateList(finalOrderedList); tvFilterCount.text = "${finalOrderedList.size} 枚"
    }

    private fun showGeneratedImagesFolderPicker() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val folderUriStr = prefs.getString("gen_save_folder_uri", null)
        if (folderUriStr == null) {
            Toast.makeText(this, "保存先フォルダが設定されてないわ！", Toast.LENGTH_SHORT).show()
            return
        }

        val folderUri = Uri.parse(folderUriStr)
        val rootDir = DocumentFile.fromTreeUri(this, folderUri)
        if (rootDir == null || !rootDir.exists()) {
            Toast.makeText(this, "フォルダにアクセスできないわ。設定を確認してね！", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFolders = rootDir.listFiles()
            .filter { it.isDirectory }
            .sortedByDescending { it.name }

        if (dateFolders.isEmpty()) {
            Toast.makeText(this, "まだ画像が生成されてないみたいよ？", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_picker, null)
        val rvFolders = dialogView.findViewById<RecyclerView>(R.id.recycler_view_tags)
        val btnDone = dialogView.findViewById<Button>(R.id.btn_dialog_done)
        btnDone.visibility = View.GONE
        
        dialogView.findViewById<View>(R.id.dialog_title).visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn_add_tag).visibility = View.GONE
        
        (dialogView.findViewById<View>(R.id.dialog_title).parent as? View)?.visibility = View.GONE
        
        if (dialogView is LinearLayout && dialogView.childCount > 1) {
            dialogView.getChildAt(1).visibility = View.GONE
        }
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setView(dialogView)
            .create()

        val adapter = GeneratedFolderAdapter(dateFolders) { folder ->
            openFolderAsAlbum(folder)
            dialog.dismiss()
        }
        rvFolders.layoutManager = GridLayoutManager(this, 2)
        rvFolders.adapter = adapter
        
        dialog.show()
    }

    private inner class GeneratedFolderAdapter(
        private val folders: List<DocumentFile>,
        private val onFolderClick: (DocumentFile) -> Unit
    ) : RecyclerView.Adapter<GeneratedFolderAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumbnail: ImageView = view.findViewById(R.id.iv_folder_thumbnail)
            val tvName: TextView = view.findViewById(R.id.tv_folder_name)
            val tvCount: TextView = view.findViewById(R.id.tv_image_count)
            val card: View = view.findViewById(R.id.card_folder)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_generated_folder, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val folder = folders[position]
            holder.tvName.text = folder.name
            
            val images = folder.listFiles().filter { it.isFile && (it.type?.startsWith("image/") == true || it.name?.endsWith(".png") == true || it.name?.endsWith(".jpg") == true) }
            holder.tvCount.text = "${images.size} 枚"
            
            if (images.isNotEmpty()) {
                Glide.with(holder.ivThumbnail.context)
                    .load(images.first().uri)
                    .centerCrop()
                    .into(holder.ivThumbnail)
            } else {
                holder.ivThumbnail.setImageResource(R.drawable.ic_folder)
                holder.ivThumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            
            holder.card.setOnClickListener { onFolderClick(folder) }
        }

        override fun getItemCount() = folders.size
    }

    private val albumDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AlbumDetailActivity.RESULT_GO_TO_FOLDER_PICKER) {
            showGeneratedImagesFolderPicker()
        }
    }

    private fun openFolderAsAlbum(folder: DocumentFile) {
        val folderUri = folder.uri.toString()
        val folderName = folder.name ?: "Unknown"
        val albumName = "生成: $folderName"
        
        val intent = Intent(this, AlbumDetailActivity::class.java).apply {
            putExtra("ALBUM_NAME", albumName)
            putExtra("FOLDER_URI", folderUri)
            val files = folder.listFiles().filter { it.isFile && (it.type?.startsWith("image/") == true || it.name?.endsWith(".png") == true || it.name?.endsWith(".jpg") == true) }
            putStringArrayListExtra("VIRTUAL_ALBUM_URIS", ArrayList(files.map { it.uri.toString() }))
        }
        albumDetailLauncher.launch(intent)
    }
}
