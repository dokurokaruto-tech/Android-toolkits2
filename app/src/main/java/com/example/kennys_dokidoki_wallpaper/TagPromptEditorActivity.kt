package com.example.kennys_dokidoki_wallpaper

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class TagPromptEditorActivity : AppCompatActivity() {

    private lateinit var etTagName: EditText
    private lateinit var etPromptInput: EditText
    private lateinit var tvTitle: TextView
    private lateinit var tvImpliedTags: TextView
    private lateinit var tvCounter: TextView
    private lateinit var tvLocalCardStatus: TextView
    private lateinit var btnLinkLocalCard: Button
    private lateinit var btnAiGenerate: Button
    private lateinit var originalTag: String
    
    private val currentImpliedTags = mutableSetOf<String>()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var generateJob: Job? = null
    private var generateMotion: AnimatorSet? = null

    private var hybridDialog: AppCompatDialog? = null
    private var ivDialogImage: ImageView? = null
    private var selectedImageUri: Uri? = null

    // --- Remote Model Data ---
    data class RemoteModel(
        val id: String,
        val name: String,
        val isFree: Boolean,
        val contextLength: Int,
        val pricePerMillion: Double,
        val created: Long
    )

    private var openRouterModels: List<RemoteModel> = emptyList()
    private var openRouterSortByDate: Boolean = true

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                selectedImageUri = uri
                ivDialogImage?.visibility = View.VISIBLE
                ivDialogImage?.setImageURI(uri)
            }
        }
    }

    private fun loadCachedOpenRouterModels() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val json = prefs.getString("cached_openrouter_models", null) ?: return
        try {
            val dataArray = JSONObject(json).getJSONArray("data")
            val newList = mutableListOf<RemoteModel>()
            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val pricing = obj.optJSONObject("pricing")
                val isFree = (pricing?.optString("prompt") == "0" || pricing?.optDouble("prompt", 1.0) == 0.0) &&
                             (pricing?.optString("completion") == "0" || pricing?.optDouble("completion", 1.0) == 0.0)
                val price = pricing?.optDouble("prompt", 0.0) ?: 0.0

                newList.add(RemoteModel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    isFree = isFree,
                    contextLength = obj.optInt("context_length", 0),
                    pricePerMillion = price * 1000000.0,
                    created = obj.optLong("created", 0)
                ))
            }
            openRouterModels = newList
        } catch (e: Exception) {
            Log.e("TagEditor", "Failed to parse cached models", e)
        }
    }

    private fun fetchOpenRouterModels(onComplete: () -> Unit) {
        Toast.makeText(this, "最新のモデルリストを取得しています...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://openrouter.ai/api/v1/models")
                val conn = url.openConnection() as HttpURLConnection
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val dataArray = JSONObject(response).getJSONArray("data")
                    val newList = mutableListOf<RemoteModel>()
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        val pricing = obj.optJSONObject("pricing")
                        val isFree = (pricing?.optString("prompt") == "0" || pricing?.optDouble("prompt", 1.0) == 0.0) &&
                                     (pricing?.optString("completion") == "0" || pricing?.optDouble("completion", 1.0) == 0.0)
                        val price = pricing?.optDouble("prompt", 0.0) ?: 0.0

                        newList.add(RemoteModel(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            isFree = isFree,
                            contextLength = obj.optInt("context_length", 0),
                            pricePerMillion = price * 1000000.0,
                            created = obj.optLong("created", 0)
                        ))
                    }
                    openRouterModels = newList
                    getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putString("cached_openrouter_models", response)
                        .apply()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TagPromptEditorActivity, "モデルリストを更新しました。", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TagPromptEditorActivity, "モデルリストの取得に失敗しました。", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tag_prompt_editor)

        originalTag = intent.getStringExtra("TAG_NAME") ?: ""
        
        tvTitle = findViewById(R.id.tv_editor_title)
        etTagName = findViewById(R.id.et_tag_name)
        etPromptInput = findViewById(R.id.et_prompt_input)
        tvImpliedTags = findViewById(R.id.tv_implied_tags_display)
        tvCounter = findViewById(R.id.tv_counter)
        tvLocalCardStatus = findViewById(R.id.tv_local_card_status)
        btnLinkLocalCard = findViewById(R.id.btn_link_local_card)
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_tag_editor)
        val btnEditImplied = findViewById<Button>(R.id.btn_edit_implied_tags)
        btnAiGenerate = findViewById(R.id.btn_ai_generate)
        val btnMigrate = findViewById<Button>(R.id.btn_migrate)
        val btnDelete = findViewById<Button>(R.id.btn_delete)
        val btnSave = findViewById<Button>(R.id.btn_save)

        toolbar.setNavigationOnClickListener { finish() }
        tvTitle.text = "タグの編集"
        etTagName.setText(originalTag)
        
        if (originalTag.isEmpty()) {
            btnDelete.visibility = View.GONE
            btnMigrate.visibility = View.GONE
        }

        btnMigrate.setOnClickListener { showMigrationDialog() }

        btnDelete.setOnClickListener {
            val usedImagesCount = DataManager.allImages.count { it.tags.contains(originalTag) }
            val usedSetsCount = DataManager.imageSetList.count { it.targetTags.contains(originalTag) }

            if (usedImagesCount > 0 || usedSetsCount > 0) {
                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("⚠️ 削除できません")
                    .setMessage("このタグ「$originalTag」は現在 ${usedImagesCount}枚の画像、または ${usedSetsCount}個のイメージセットで使用中よ！\n危険だから削除は許可しないわ。先に画像やセットからこのタグを外してきてね！")
                    .setPositiveButton("わかった", null)
                    .show()
            } else {
                AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("タグの削除")
                    .setMessage("「$originalTag」を削除してもいいの？")
                    .setPositiveButton("削除する") { _, _ ->
                        TagManager.deleteTag(this, originalTag)
                        Toast.makeText(this, "「$originalTag」を消し去ったわ！", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    .setNegativeButton("やっぱりやめる", null)
                    .show()
            }
        }

        val initialPrompt = TagManager.getTagPrompt(originalTag)
        etPromptInput.setText(initialPrompt)
        updateCounter(initialPrompt)
        
        PromptCardManager.loadCards(this)
        updateLocalCardStatus()

        etPromptInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCounter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val implied = TagManager.getImpliedTags(originalTag)
        currentImpliedTags.addAll(implied)
        updateImpliedTagsDisplay()

        btnEditImplied.setOnClickListener { showImpliedTagsPickerDialog() }
        btnLinkLocalCard.setOnClickListener { showLocalCardPickerDialog() }
        btnAiGenerate.setOnClickListener { showHybridGenerateDialog() }
        btnSave.setOnClickListener {
            val newTagName = etTagName.text.toString().trim()
            val newPrompt = etPromptInput.text.toString().trim()

            if (newTagName.isEmpty()) {
                Toast.makeText(this, "タグ名を入力してください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newTagName != originalTag) {
                DataManager.allImages.forEach { entry ->
                    if (entry.tags.contains(originalTag)) {
                        entry.tags.remove(originalTag)
                        entry.tags.add(newTagName)
                    }
                }
                DataManager.imageSetList.forEach { set ->
                    if (set.targetTags.contains(originalTag)) {
                        set.targetTags.remove(originalTag)
                        set.targetTags.add(newTagName)
                    }
                }
                val remoteId = TagManager.tagRemoteCardIds.remove(originalTag)
                if (remoteId != null) TagManager.tagRemoteCardIds[newTagName] = remoteId
                TagManager.renameTag(this, originalTag, newTagName)
            }
            
            TagManager.setTagPrompt(this, newTagName, newPrompt)
            TagManager.setImpliedTags(this, newTagName, currentImpliedTags)
            DataManager.saveData(this)
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showMigrationDialog() {
        val selectedDestinationTags = mutableSetOf<String>()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_picker, null)
        val rvTags = dialogView.findViewById<RecyclerView>(R.id.recycler_view_tags)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val btnDone = dialogView.findViewById<Button>(R.id.btn_dialog_done)
        
        tvDialogTitle.text = "移住先のタグを選択（複数可）"
        
        // 自身への移住は禁止
        val pickerAdapter = CategorizedTagPickerAdapter(selectedDestinationTags, excludeCategory = null) { _, _ -> }
        // 注意：CategorizedTagPickerAdapterの中で自分自身を除外するロジックがない場合、
        // 移住先リストから自分を消す必要があるけど、とりあえずここでは単純に進めるわ。
        
        rvTags.layoutManager = GridLayoutManager(this, 3)
        rvTags.adapter = pickerAdapter
        pickerAdapter.refreshItems(this)
        
        val pickerDialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()
        
        btnDone.setOnClickListener {
            if (selectedDestinationTags.isEmpty()) {
                Toast.makeText(this, "移住先を選んでね！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedDestinationTags.contains(originalTag)) {
                Toast.makeText(this, "自分自身には移住できないわよ！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            pickerDialog.dismiss()
            
            // 移住後の削除オプションを確認
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("タグの移住")
                .setMessage("「$originalTag」を持っている全ての画像に、選択したタグを追加するわよ！\n移住が終わった後、元のタグ「$originalTag」を削除する？")
                .setPositiveButton("移住して元タグを削除") { _, _ -> executeMigration(selectedDestinationTags, deleteSource = true) }
                .setNeutralButton("移住だけする（元タグ維持）") { _, _ -> executeMigration(selectedDestinationTags, deleteSource = false) }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        pickerDialog.show()
    }

    private fun executeMigration(destTags: Set<String>, deleteSource: Boolean) {
        var count = 0
        DataManager.allImages.forEach { entry ->
            if (entry.tags.contains(originalTag)) {
                entry.tags.addAll(destTags)
                if (deleteSource) {
                    entry.tags.remove(originalTag)
                }
                count++
            }
        }
        
        // イメージセットも更新しちゃうわ！
        DataManager.imageSetList.forEach { set ->
            if (set.targetTags.contains(originalTag)) {
                set.targetTags.addAll(destTags)
                if (deleteSource) {
                    set.targetTags.remove(originalTag)
                }
            }
        }

        if (deleteSource) {
            TagManager.deleteTag(this, originalTag)
        }
        
        DataManager.saveData(this)
        Toast.makeText(this, "${count}枚の画像をお引越しさせたわよ！", Toast.LENGTH_SHORT).show()
        
        if (deleteSource) {
            finish()
        }
    }

    private fun updateCounter(text: String) {
        val chars = text.length
        val tokens = TagManager.estimateTokenCount(text)
        tvCounter.text = "$chars 文字 | 約 $tokens トークン"
        if (chars in 300..500) tvCounter.setTextColor(android.graphics.Color.parseColor("#D0BCFF"))
        else tvCounter.setTextColor(android.graphics.Color.parseColor("#CAC4D0"))
    }

    private fun updateImpliedTagsDisplay() {
        if (currentImpliedTags.isEmpty()) {
            tvImpliedTags.text = "なし"
            tvImpliedTags.setTextColor(Color.GRAY)
        } else {
            tvImpliedTags.text = currentImpliedTags.joinToString(", ")
            tvImpliedTags.setTextColor(android.graphics.Color.parseColor("#D0BCFF"))
        }
    }

    private fun updateLocalCardStatus() {
        // 全てのカードの中から、このタグ(originalTag)が appliedTags に含まれているものを探すわ
        val linkedCard = PromptCardManager.promptCards.find { it.appliedTags.contains(originalTag) }
        
        if (linkedCard != null) {
            tvLocalCardStatus.text = "紐付け済み: 🎴 [${linkedCard.category}] ${linkedCard.label}"
            tvLocalCardStatus.setTextColor(android.graphics.Color.parseColor("#D0BCFF"))
        } else {
            tvLocalCardStatus.text = "紐付けられたカード: なし"
            tvLocalCardStatus.setTextColor(android.graphics.Color.parseColor("#FFCC00"))
        }
    }

    private fun showImpliedTagsPickerDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_picker, null)
        val rvTags = dialogView.findViewById<RecyclerView>(R.id.recycler_view_tags)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val btnDone = dialogView.findViewById<Button>(R.id.btn_dialog_done)
        tvDialogTitle.text = "自動付与するタグを選択"
        val pickerAdapter = CategorizedTagPickerAdapter(currentImpliedTags, excludeCategory = null) { _, _ -> }
        rvTags.layoutManager = GridLayoutManager(this, 3)
        rvTags.adapter = pickerAdapter
        pickerAdapter.refreshItems(this)
        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper).setView(dialogView).create()
        btnDone.setOnClickListener { updateImpliedTagsDisplay(); dialog.dismiss() }
        dialog.show()
    }

    private fun showHybridGenerateDialog() {
        loadCachedOpenRouterModels()
        selectedImageUri = null
        val (_, dialogView) = Md3PopupDialog.inflate(this, R.layout.dialog_ai_generate)
        val etInstruction = dialogView.findViewById<EditText>(R.id.et_instruction)
        val cbUseTagName = dialogView.findViewById<android.widget.CompoundButton>(R.id.cb_use_tag_name)
        val cbUseExisting = dialogView.findViewById<android.widget.CompoundButton>(R.id.cb_use_existing)
        val btnPickImage = dialogView.findViewById<Button>(R.id.btn_pick_image)
        ivDialogImage = dialogView.findViewById(R.id.iv_selected_image)
        val cardSelectedImage = dialogView.findViewById<View>(R.id.card_selected_image)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val btnGenerate = dialogView.findViewById<Button>(R.id.btn_generate)
        val tvModelInfo = dialogView.findViewById<TextView>(R.id.tv_model_info)
        val tvUsageCounter = dialogView.findViewById<TextView>(R.id.tv_usage_counter)
        val btnChangeModel = dialogView.findViewById<Button>(R.id.btn_change_model)
        val cgProfiles = dialogView.findViewById<ChipGroup>(R.id.cg_prompt_profiles)
        val btnEditPresets = dialogView.findViewById<Button>(R.id.btn_edit_instruction_profiles)
        val btnDeletePreset = dialogView.findViewById<Button>(R.id.btn_delete_instruction_preset)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        var selectedPresetName: String? = null
        var selectedSystemPrompt = TagInstructionPolicy.DEFAULT_PROMPT

        fun bindPresets() {
            val profiles = TagInstructionPolicy.parse(
                prefs.getString(TagInstructionPolicy.PROFILES_KEY, null),
                prefs.getString(TagInstructionPolicy.LEGACY_KEY, null)
            )
            val selected = TagInstructionPolicy.selectedIndex(profiles, selectedPresetName)
            selectedPresetName = profiles[selected].name
            selectedSystemPrompt = profiles[selected].content
            cgProfiles.removeAllViews()
            profiles.forEachIndexed { index, profile ->
                val chip = Chip(dialogView.context).apply {
                    text = profile.name
                    isCheckable = true
                    isChecked = index == selected
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            selectedPresetName = profile.name
                            selectedSystemPrompt = profile.content
                            btnDeletePreset.isEnabled = TagInstructionPolicy.canDelete(profiles)
                        }
                    }
                }
                cgProfiles.addView(chip)
            }
            btnDeletePreset.isEnabled = TagInstructionPolicy.canDelete(profiles)
        }
        bindPresets()
        btnEditPresets.setOnClickListener {
            TagInstructionEditor.show(this) { bindPresets() }
        }
        btnDeletePreset.setOnClickListener {
            TagInstructionEditor.delete(this, selectedPresetName) { bindPresets() }
        }

        fun updateDialogModelStatus() {
            val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
            val model = if (provider == "OPENROUTER") {
                prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free")
            } else {
                "grok-4-1-fast-non-reasoning"
            }
            tvModelInfo.text = TagAiGenerateCopy.modelLine(provider, model.orEmpty())
            if (provider == "OPENROUTER") {
                val keys = OpenRouterManager.getApiKeys(this)
                val limit = keys.size * 50
                tvUsageCounter.text = TagAiGenerateCopy.usageLine(OpenRouterManager.getTotalUsage(this), limit)
                tvUsageCounter.visibility = View.VISIBLE
            } else {
                tvUsageCounter.visibility = View.GONE
            }
        }
        updateDialogModelStatus()
        btnChangeModel.setOnClickListener {
            showProviderSelectionDialog { updateDialogModelStatus() }
        }

        cbUseTagName.isChecked = etTagName.text.toString().trim().isNotEmpty()
        cbUseExisting.isChecked = etPromptInput.text.toString().trim().isNotEmpty()
        hybridDialog = Md3PopupDialog.show(this, dialogView)

        btnPickImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            pickImageLauncher.launch(intent)
        }
        coroutineScope.launch {
            while (hybridDialog?.isShowing == true) {
                if (selectedImageUri != null) cardSelectedImage.visibility = View.VISIBLE
                kotlinx.coroutines.delay(500)
            }
        }
        btnCancel.setOnClickListener { hybridDialog?.dismiss() }
        btnGenerate.setOnClickListener {
            generatePromptHybrid(
                etInstruction.text.toString().trim(),
                cbUseTagName.isChecked,
                cbUseExisting.isChecked,
                selectedImageUri,
                selectedSystemPrompt
            )
            hybridDialog?.dismiss()
        }
    }

    override fun onDestroy() {
        stopGenerateMotion()
        generateJob?.cancel()
        super.onDestroy()
    }

    private fun showProviderSelectionDialog(onUpdated: () -> Unit) {
        val options = arrayOf("xAI (Grok) - 高速・高精度", "OpenRouter - 多彩なモデル")
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("推論プロバイダーを選択")
            .setItems(options) { _, which ->
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (which == 0) {
                    prefs.edit().putString("chat_cloud_provider", "GROK").apply()
                    onUpdated()
                } else {
                    prefs.edit().putString("chat_cloud_provider", "OPENROUTER").apply()
                    showOpenRouterModelSelectionDialog(onUpdated)
                }
            }.show()
    }

    private fun showOpenRouterModelSelectionDialog(onUpdated: () -> Unit) {
        val options = arrayOf("Free Models (無料)", "Paid Models (有料)", "モデルリストを更新")
        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("OpenRouter カテゴリ")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showOpenRouterModelListDialog(true, onUpdated)
                    1 -> showOpenRouterModelListDialog(false, onUpdated)
                    2 -> fetchOpenRouterModels { showOpenRouterModelSelectionDialog(onUpdated) }
                }
            }.show()
    }

    private fun showOpenRouterModelListDialog(freeOnly: Boolean, onUpdated: () -> Unit) {
        if (openRouterModels.isEmpty()) {
            fetchOpenRouterModels { showOpenRouterModelListDialog(freeOnly, onUpdated) }
            return
        }

        val filtered = if (freeOnly) openRouterModels.filter { it.isFree } else openRouterModels
        val sorted = if (openRouterSortByDate) {
            filtered.sortedByDescending { it.created }
        } else {
            filtered.sortedBy { it.name.lowercase() }
        }

        val items = sorted.map { model ->
            val priceStr = if (model.isFree) "Free" else "$${String.format("%.2f", model.pricePerMillion)}/M"
            "${model.name} ($priceStr)"
        }.toTypedArray()

        AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle(if (freeOnly) "無料モデルを選択" else "全モデルを選択")
            .setItems(items) { _, which ->
                val selectedModel = sorted[which]
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                prefs.edit().putString("chat_openrouter_model", selectedModel.id).apply()
                Toast.makeText(this, "${selectedModel.name} を選択しました。", Toast.LENGTH_SHORT).show()
                onUpdated()
            }
            .setNeutralButton(if (openRouterSortByDate) "名前順にする" else "新着順にする") { _, _ ->
                openRouterSortByDate = !openRouterSortByDate
                showOpenRouterModelListDialog(freeOnly, onUpdated)
            }
            .setNegativeButton("戻る") { _, _ -> showOpenRouterModelSelectionDialog(onUpdated) }
            .show()
    }

    private fun showLocalCardPickerDialog() {
        val cards = PromptCardManager.promptCards
        if (cards.isEmpty()) {
            Toast.makeText(this, "Prompt Builder にカードが1枚もないわよ！", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tag_picker, null)
        val rvCards = dialogView.findViewById<RecyclerView>(R.id.recycler_view_tags)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.dialog_title)
        val btnDone = dialogView.findViewById<Button>(R.id.btn_dialog_done)
        
        tvDialogTitle.text = "紐付けるカードを選択"
        btnDone.visibility = View.GONE // タップで決定するので不要
        
        var dialog: AlertDialog? = null
        val pickerAdapter = TagCardPickerAdapter(cards) { selected ->
            // 他のカードからこのタグの紐付けを解除
            PromptCardManager.promptCards.forEach { it.appliedTags.remove(originalTag) }
            // 新しいカードにこのタグを追加
            selected.appliedTags.add(originalTag)
            
            PromptCardManager.saveCards(this)
            updateLocalCardStatus()
            Toast.makeText(this, "「${selected.label}」と紐付けたわ！", Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
        }
        
        val gridLayoutManager = GridLayoutManager(this, 3)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = pickerAdapter.getSpanSize(position, 3)
        }
        
        rvCards.layoutManager = gridLayoutManager
        rvCards.adapter = pickerAdapter
        
        dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setView(dialogView)
            .setNeutralButton("紐付け解除") { _, _ ->
                PromptCardManager.promptCards.forEach { it.appliedTags.remove(originalTag) }
                PromptCardManager.saveCards(this)
                updateLocalCardStatus()
                Toast.makeText(this, "紐付けを解除したわよ！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.show()
    }

    private fun generatePromptHybrid(instruction: String, useTagName: Boolean, useExisting: Boolean, imageUri: Uri?, systemPrompt: String) {
        if (generateJob?.isActive == true) return
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
        val originalPrompt = etPromptInput.text.toString()
        val userText = TagPromptStreamPolicy.userMessage(
            etTagName.text.toString(),
            instruction,
            originalPrompt,
            useTagName,
            useExisting
        )
        startGenerateMotion()
        generateJob = coroutineScope.launch(Dispatchers.IO) {
            var failed = true
            try {
                val imageDataUrl = imageUri?.let { uri ->
                    encodeImageToBase64(uri)?.let { "data:image/jpeg;base64,$it" }
                }
                val reply = when {
                    provider == "OPENROUTER" -> streamOpenRouter(prefs, systemPrompt, userText, imageDataUrl)
                    else -> streamGrokOrLegacy(prefs, instruction, useTagName, useExisting, originalPrompt, systemPrompt, userText, imageDataUrl)
                }
                if (reply.isNotEmpty()) {
                    failed = false
                    withContext(Dispatchers.Main) {
                        etPromptInput.setText(reply)
                        Toast.makeText(this@TagPromptEditorActivity, "置き換えた。", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("TagEditor", "Generation Failed", e)
            }
            withContext(Dispatchers.Main) {
                if (failed) {
                    etPromptInput.setText(TagPromptStreamPolicy.applyFailedInstruction(originalPrompt, instruction))
                    val message = if (instruction.isNotBlank()) {
                        "置き換えに失敗した。補足を文章へ移した。"
                    } else {
                        "置き換えに失敗した。"
                    }
                    Toast.makeText(this@TagPromptEditorActivity, message, Toast.LENGTH_SHORT).show()
                }
                stopGenerateMotion()
            }
        }
    }

    private suspend fun streamOpenRouter(
        prefs: android.content.SharedPreferences,
        systemPrompt: String,
        userText: String,
        imageDataUrl: String?
    ): String {
        val apiKey = OpenRouterManager.getActiveApiKey(this@TagPromptEditorActivity)
            ?: throw IllegalStateException("OpenRouterのAPIキーがない")
        val modelName = prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free")
            ?: "deepseek/deepseek-v4-flash:free"
        val reply = TagPromptStreamClient.stream(
            TagPromptStreamClient.Endpoint(
                url = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = apiKey,
                model = modelName,
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/example/android-toolkits",
                    "X-Title" to "Android Toolkits"
                )
            ),
            systemPrompt,
            userText,
            imageDataUrl,
            isActive = { generateJob?.isActive == true },
            onDelta = { text -> coroutineScope.launch(Dispatchers.Main) { etPromptInput.setText(text) } }
        )
        if (reply.isNotEmpty()) {
            OpenRouterManager.incrementUsage(this@TagPromptEditorActivity, apiKey)
        }
        return reply
    }

    private suspend fun streamGrokOrLegacy(
        prefs: android.content.SharedPreferences,
        instruction: String,
        useTagName: Boolean,
        useExisting: Boolean,
        originalPrompt: String,
        systemPrompt: String,
        userText: String,
        imageDataUrl: String?
    ): String {
        var xaiApiKey = prefs.getString("xai_api_key", "")?.trim().orEmpty()
        if (xaiApiKey.isNotEmpty() && !xaiApiKey.startsWith("xai-")) xaiApiKey = "xai-$xaiApiKey"
        if (xaiApiKey.isNotEmpty()) {
            return TagPromptStreamClient.stream(
                TagPromptStreamClient.Endpoint(
                    url = "https://api.x.ai/v1/chat/completions",
                    apiKey = xaiApiKey,
                    model = "grok-4-1-fast-non-reasoning"
                ),
                systemPrompt,
                userText,
                imageDataUrl,
                isActive = { generateJob?.isActive == true },
                onDelta = { text -> coroutineScope.launch(Dispatchers.Main) { etPromptInput.setText(text) } }
            )
        }
        val baseUrl = prefs.getString("remote_server_url", "") ?: ""
        if (baseUrl.isEmpty()) throw IllegalStateException("生成先がない")
        val url = URL("$baseUrl/api/generate-prompt")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            val agentKey = prefs.getString("generation_agent_api_key", "")?.trim().orEmpty()
            if (agentKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $agentKey")
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
        }
        val requestBody = JSONObject().apply {
            put("tagName", if (useTagName) etTagName.text.toString().trim() else "")
            put("instruction", instruction)
            put("existingPrompt", if (useExisting) originalPrompt.trim() else "")
            put("systemPrompt", systemPrompt)
            if (!imageDataUrl.isNullOrBlank()) put("image", imageDataUrl)
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
        val reply = if (conn.responseCode == 200) {
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("prompt")
        } else {
            ""
        }
        conn.disconnect()
        if (reply.isNotEmpty()) {
            withContext(Dispatchers.Main) { etPromptInput.setText(reply) }
        }
        return reply
    }

    private fun startGenerateMotion() {
        stopGenerateMotion()
        btnAiGenerate.isEnabled = false
        btnAiGenerate.contentDescription = "置き換え中"
        val spin = ObjectAnimator.ofFloat(btnAiGenerate, View.ROTATION, 0f, 360f).apply {
            duration = 1100
            interpolator = LinearInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }
        val pulse = ObjectAnimator.ofFloat(btnAiGenerate, View.ALPHA, 1f, 0.4f, 1f).apply {
            duration = 700
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ObjectAnimator.INFINITE
        }
        generateMotion = AnimatorSet().apply {
            playTogether(spin, pulse)
            start()
        }
    }

    private fun stopGenerateMotion() {
        generateMotion?.cancel()
        generateMotion = null
        if (::btnAiGenerate.isInitialized) {
            btnAiGenerate.animate().cancel()
            btnAiGenerate.rotation = 0f
            btnAiGenerate.alpha = 1f
            btnAiGenerate.isEnabled = true
            btnAiGenerate.contentDescription = "AIで置き換える"
        }
    }

    private fun encodeImageToBase64(uri: Uri): String? {
        return try {
            val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }
}
