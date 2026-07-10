package com.example.kennys_dokidoki_wallpaper

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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import org.json.JSONArray
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
    private lateinit var originalTag: String
    
    private val currentImpliedTags = mutableSetOf<String>()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var hybridDialog: AlertDialog? = null
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

    private val defaultSystemPrompt = """
        あなたはAIキャラクターチャットのコンテキスト設計におけるスペシャリストです。
        このアプリは、複数の独立した『タグ』をパズルのように組み合わせることで一人のキャラクターを完成させる『非破壊的・プロシージャル・コンテキスト・システム』を採用しています。
        ターゲットタグの内容を300文字から500文字以内で、純粋な設定文として出力してください。
    """.trimIndent()

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
        val btnEditImplied = findViewById<Button>(R.id.btn_edit_implied_tags)
        val btnGenerate = findViewById<Button>(R.id.btn_ai_generate)
        val btnMigrate = findViewById<Button>(R.id.btn_migrate)
        val btnDelete = findViewById<Button>(R.id.btn_delete)
        val btnSave = findViewById<Button>(R.id.btn_save)

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
        btnGenerate.setOnClickListener { showHybridGenerateDialog() }
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
        if (chars in 300..500) tvCounter.setTextColor(Color.parseColor("#8AB4F8"))
        else tvCounter.setTextColor(Color.parseColor("#9AA0A6"))
    }

    private fun updateImpliedTagsDisplay() {
        if (currentImpliedTags.isEmpty()) {
            tvImpliedTags.text = "なし"
            tvImpliedTags.setTextColor(Color.GRAY)
        } else {
            tvImpliedTags.text = currentImpliedTags.joinToString(", ")
            tvImpliedTags.setTextColor(Color.parseColor("#8AB4F8"))
        }
    }

    private fun updateLocalCardStatus() {
        // 全てのカードの中から、このタグ(originalTag)が appliedTags に含まれているものを探すわ
        val linkedCard = PromptCardManager.promptCards.find { it.appliedTags.contains(originalTag) }
        
        if (linkedCard != null) {
            tvLocalCardStatus.text = "紐付け済み: 🎴 [${linkedCard.category}] ${linkedCard.label}"
            tvLocalCardStatus.setTextColor(Color.parseColor("#8AB4F8"))
        } else {
            tvLocalCardStatus.text = "紐付けられたカード: なし"
            tvLocalCardStatus.setTextColor(Color.parseColor("#FDD663"))
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_generate, null)
        val etInstruction = dialogView.findViewById<EditText>(R.id.et_instruction)
        val cbUseTagName = dialogView.findViewById<CheckBox>(R.id.cb_use_tag_name)
        val cbUseExisting = dialogView.findViewById<CheckBox>(R.id.cb_use_existing)
        val btnPickImage = dialogView.findViewById<Button>(R.id.btn_pick_image)
        ivDialogImage = dialogView.findViewById<ImageView>(R.id.iv_selected_image)
        val cardSelectedImage = dialogView.findViewById<View>(R.id.card_selected_image)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val btnGenerate = dialogView.findViewById<Button>(R.id.btn_generate)
        
        val tvModelInfo = dialogView.findViewById<TextView>(R.id.tv_model_info)
        val tvUsageCounter = dialogView.findViewById<TextView>(R.id.tv_usage_counter)
        val btnChangeModel = dialogView.findViewById<Button>(R.id.btn_change_model)

        val cgProfiles = dialogView.findViewById<ChipGroup>(R.id.cg_prompt_profiles)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val profilesJson = prefs.getString("ai_system_prompt_profiles", null)
        val profiles = mutableListOf<JSONObject>()
        if (profilesJson != null) {
            val arr = JSONArray(profilesJson)
            for (i in 0 until arr.length()) profiles.add(arr.getJSONObject(i))
        } else {
            // 旧設定の引き継ぎ
            val old = prefs.getString("ai_system_prompt", null)
            profiles.add(JSONObject().apply { put("name", "メイン"); put("content", old ?: defaultSystemPrompt) })
        }

        var selectedSystemPrompt = profiles.first().getString("content")

        profiles.forEachIndexed { index, profile ->
            val chip = Chip(this).apply {
                text = profile.getString("name")
                isCheckable = true
                if (index == 0) isChecked = true
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedSystemPrompt = profile.getString("content")
                }
                setTextColor(Color.WHITE)
                setChipBackgroundColorResource(android.R.color.transparent)
                setChipStrokeColorResource(if (isChecked) android.R.color.white else android.R.color.darker_gray)
                // Material3のチップはスタイル設定がちょっと面倒だけど、とりあえず基本機能で動かすわ
            }
            cgProfiles.addView(chip)
        }

        fun updateDialogModelStatus() {
            val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
            val model = if (provider == "OPENROUTER") {
                prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free")
            } else {
                "grok-4-1-fast-non-reasoning"
            }
            tvModelInfo.text = "Model: $provider / $model"
            
            if (provider == "OPENROUTER") {
                val total = OpenRouterManager.getTotalUsage(this)
                val keys = OpenRouterManager.getApiKeys(this)
                tvUsageCounter.text = "OR: $total/${keys.size * 50}"
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
        
        hybridDialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setView(dialogView)
            .create()
        
        hybridDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPickImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }
            pickImageLauncher.launch(intent)
        }
        
        // 画像が選択されたらカードを表示するようにするわ
        coroutineScope.launch {
            while(hybridDialog?.isShowing == true) {
                if (selectedImageUri != null) {
                    cardSelectedImage?.visibility = View.VISIBLE
                }
                kotlinx.coroutines.delay(500)
            }
        }

        btnCancel.setOnClickListener { hybridDialog?.dismiss() }
        btnGenerate.setOnClickListener {
            generatePromptHybrid(etInstruction.text.toString().trim(), cbUseTagName.isChecked, cbUseExisting.isChecked, selectedImageUri, selectedSystemPrompt)
            hybridDialog?.dismiss()
        }
        hybridDialog?.show()
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
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
        
        Toast.makeText(this, "AIが考えてるわよ...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch(Dispatchers.IO) {
            try {
                var reply = ""
                if (provider == "OPENROUTER") {
                    val apiKey = OpenRouterManager.getActiveApiKey(this@TagPromptEditorActivity)
                    if (apiKey == null) {
                        withContext(Dispatchers.Main) { Toast.makeText(this@TagPromptEditorActivity, "OpenRouterのAPIキーがないわよ！", Toast.LENGTH_SHORT).show() }
                        return@launch
                    }
                    val modelName = prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free") ?: "deepseek/deepseek-v4-flash:free"
                    
                    val url = URL("https://openrouter.ai/api/v1/chat/completions")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                    }
                    
                    val userText = "ターゲット: ${if (useTagName) etTagName.text.toString() else ""}\n指示: $instruction\n${if (useExisting) "既存設定: " + etPromptInput.text.toString() else ""}"
                    
                    val messages = JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userText) })
                    }
                    
                    val requestBody = JSONObject().apply {
                        put("model", modelName)
                        put("messages", messages)
                    }
                    
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
                    
                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        reply = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        OpenRouterManager.incrementUsage(this@TagPromptEditorActivity, apiKey)
                    } else {
                        Log.e("TagEditor", "OpenRouter Error: ${conn.responseCode} ${conn.errorStream?.bufferedReader()?.use { it.readText() }}")
                    }
                    conn.disconnect()
                } else {
                    val baseUrl = prefs.getString("remote_server_url", "") ?: ""
                    var xaiApiKey = prefs.getString("xai_api_key", "")?.trim() ?: ""
                    if (xaiApiKey.isNotEmpty() && !xaiApiKey.startsWith("xai-")) xaiApiKey = "xai-$xaiApiKey"
                    
                    if (baseUrl.isNotEmpty()) {
                        val url = URL("$baseUrl/api/generate-prompt")
                        val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); doOutput = true }
                        val requestBody = JSONObject().apply {
                            put("tagName", if (useTagName) etTagName.text.toString().trim() else "")
                            put("instruction", instruction)
                            put("existingPrompt", if (useExisting) etPromptInput.text.toString().trim() else "")
                            put("systemPrompt", systemPrompt)
                            imageUri?.let { val b64 = encodeImageToBase64(it); if (b64 != null) put("image", "data:image/jpeg;base64,$b64") }
                        }
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
                        if (conn.responseCode == 200) {
                            reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("prompt")
                        }
                        conn.disconnect()
                    } else if (xaiApiKey.isNotEmpty()) {
                        val url = URL("https://api.x.ai/v1/chat/completions")
                        val conn = (url.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; setRequestProperty("Authorization", "Bearer $xaiApiKey"); setRequestProperty("Content-Type", "application/json"); doOutput = true }
                        val jsonArray = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
                        var userText = "ターゲット: ${if (useTagName) etTagName.text.toString() else ""}\n指示: $instruction\n${if (useExisting) "既存設定: " + etPromptInput.text.toString() else ""}"
                        jsonArray.put(JSONObject().put("role", "user").put("content", userText))
                        val requestBody = JSONObject().put("messages", jsonArray).put("model", "grok-4-1-fast-non-reasoning")
                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
                        if (conn.responseCode == 200) {
                            reply = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        }
                        conn.disconnect()
                    }
                }

                if (reply.isNotEmpty()) {
                    withContext(Dispatchers.Main) { 
                        etPromptInput.setText(reply)
                        Toast.makeText(this@TagPromptEditorActivity, "AIが錬成したわ！", Toast.LENGTH_SHORT).show() 
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(this@TagPromptEditorActivity, "AIがサボっちゃったみたい...", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { 
                Log.e("TagEditor", "Generation Failed", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@TagPromptEditorActivity, "エラーよ！", Toast.LENGTH_SHORT).show() } 
            }
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
