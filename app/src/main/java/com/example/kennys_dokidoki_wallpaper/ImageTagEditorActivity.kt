package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import android.graphics.BitmapFactory
import android.util.Base64
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

class ImageTagEditorActivity : AppCompatActivity() {

    private var imageEntry: ImageEntry? = null
    private var imageSet: ImageSet? = null
    private var selectedUris: ArrayList<String>? = null
    
    private lateinit var btnDirectDescription: Button
    private val selectedTags = mutableSetOf<String>()
    private val initialEffectiveTags = mutableSetOf<String>()
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    
    private var isImageMode = true
    private var isNewSetMode = false
    private var isBatchMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_tag_editor)

        val uriString = intent.getStringExtra("IMAGE_URI")
        val setName = intent.getStringExtra("SET_NAME")
        selectedUris = intent.getStringArrayListExtra("SELECTED_URIS")
        isNewSetMode = intent.getBooleanExtra("CREATE_NEW_SET", false)
        
        DataManager.loadData(this)
        TagManager.loadTags(this)

        if (selectedUris != null) {
            isBatchMode = true
            isImageMode = true
            
            val allSelectedEntries = DataManager.allImages.filter { 
                selectedUris!!.contains(it.uri.toString()) 
            }
            
            if (allSelectedEntries.isNotEmpty()) {
                imageEntry = allSelectedEntries[0]
                
                val common = TagManager.getEffectiveTags(allSelectedEntries[0].tags).toMutableSet()
                for (i in 1 until allSelectedEntries.size) {
                    common.retainAll(TagManager.getEffectiveTags(allSelectedEntries[i].tags))
                }
                selectedTags.addAll(common)
                initialEffectiveTags.addAll(common)
            }
        } else if (uriString != null) {
            isImageMode = true
            val uri = Uri.parse(uriString)
            imageEntry = DataManager.allImages.find { it.uri.toString() == uri.toString() }
            if (imageEntry == null) {
                Toast.makeText(this, "画像データが見つかりません。", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val effective = TagManager.getEffectiveTags(imageEntry!!.tags)
            selectedTags.addAll(effective)
            initialEffectiveTags.addAll(effective)
        } else if (setName != null) {
            isImageMode = false
            imageSet = DataManager.imageSetList.find { it.name == setName }
            if (imageSet == null) {
                Toast.makeText(this, "セットが見つかりません。", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            selectedTags.addAll(imageSet!!.targetTags)
        } else if (isNewSetMode) {
            isImageMode = false
            imageSet = ImageSet("")
        } else {
            finish()
            return
        }

        setupUI()
    }

    private fun setupUI() {
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        val ivPreview = findViewById<ImageView>(R.id.iv_editor_preview)
        val rvTags = findViewById<RecyclerView>(R.id.rv_tag_picker)
        val btnAddTag = findViewById<Button>(R.id.btn_add_new_tag)
        val btnSave = findViewById<Button>(R.id.btn_save)
        val btnCancel = findViewById<Button>(R.id.btn_cancel)
        btnDirectDescription = findViewById(R.id.btn_direct_description)
        val headerContainer = findViewById<LinearLayout>(R.id.header_container)

        var nameInput: EditText? = null
        var radioGroup: RadioGroup? = null
        var cbActive: CheckBox? = null
        var usageRadioGroup: RadioGroup? = null

        if (isImageMode) {
            if (isBatchMode) {
                tvTitle.text = "一括属性編集 (${selectedUris?.size}枚)"
                findViewById<TextView>(R.id.tv_image_info).text = "共通属性を編集（自動付与分も含む）"
                btnDirectDescription.visibility = View.GONE
            } else {
                tvTitle.text = "画像属性の編集"
            }
            
            if (imageEntry != null) {
                Glide.with(this)
                    .load(imageEntry!!.uri)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop()
                    .into(ivPreview)
            }
        } else {
            tvTitle.text = if (isNewSetMode) "新しいイメージセットを作成" else "イメージセットの編集"
            findViewById<View>(R.id.card_image_preview).visibility = View.GONE
            findViewById<View>(R.id.image_info_layout).visibility = View.GONE
            btnDirectDescription.visibility = View.GONE
            
            val isAutoNamed = imageSet!!.isAutoGenerated || imageSet!!.name.isEmpty()

            nameInput = EditText(this).apply {
                hint = "セット名を入力...（空欄なら自動生成）"
                setText(if (isAutoNamed) "" else imageSet!!.name)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                id = View.generateViewId()
            }
            headerContainer.addView(nameInput)

            cbActive = CheckBox(this).apply {
                text = "このセットをアクティブにする"
                setTextColor(Color.WHITE)
                isChecked = imageSet?.isActive ?: true
                setPadding(0, 16, 0, 8)
            }
            headerContainer.addView(cbActive)

            radioGroup = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, 8, 0, 16)
                id = View.generateViewId()
            }
            val rbAny = RadioButton(this).apply {
                text = "いずれかのタグが一致 (OR検索)"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            val rbAll = RadioButton(this).apply {
                text = "すべてのタグが一致 (AND検索)"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            radioGroup.addView(rbAny)
            radioGroup.addView(rbAll)
            if (imageSet!!.matchMode == MatchMode.ALL) rbAll.isChecked = true else rbAny.isChecked = true
            headerContainer.addView(radioGroup)

            val tvUsageLabel = TextView(this).apply {
                text = "適用対象の属性"
                setTextColor(Color.parseColor("#8AB4F8"))
                textSize = 14f
                setPadding(0, 16, 0, 8)
            }
            headerContainer.addView(tvUsageLabel)

            usageRadioGroup = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
                setPadding(0, 8, 0, 16)
                id = View.generateViewId()
            }
            val rbBoth = RadioButton(this).apply {
                text = "両方 (ホームスクリーン & キャラチャット)"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            val rbHomescreen = RadioButton(this).apply {
                text = "ホームスクリーン専用"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            val rbChat = RadioButton(this).apply {
                text = "キャラチャット専用"
                setTextColor(Color.WHITE)
                id = View.generateViewId()
            }
            usageRadioGroup!!.addView(rbBoth)
            usageRadioGroup!!.addView(rbHomescreen)
            usageRadioGroup!!.addView(rbChat)

            when (imageSet!!.usage) {
                ImageSetUsage.BOTH -> rbBoth.isChecked = true
                ImageSetUsage.HOMESCREEN -> rbHomescreen.isChecked = true
                ImageSetUsage.CHAT -> rbChat.isChecked = true
            }
            headerContainer.addView(usageRadioGroup)

            radioGroup.setOnCheckedChangeListener { _, _ ->
                updateAutoName(nameInput, radioGroup)
            }
            
            updateAutoName(nameInput, radioGroup)
        }

        btnDirectDescription.setOnClickListener {
            imageEntry?.let { showDirectDescriptionDialog(it) }
        }

        val pickerAdapter = CategorizedTagPickerAdapter(selectedTags) { _, _ ->
            if (!isImageMode) {
                updateAutoName(nameInput, radioGroup)
            }
        }

        val layoutManager = GridLayoutManager(this, 3)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (pickerAdapter.getItemViewType(position) == 0) 3 else 1
            }
        }
        rvTags.layoutManager = layoutManager
        rvTags.adapter = pickerAdapter
        pickerAdapter.refreshItems(this)

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (pickerAdapter.isDraggable(viewHolder.adapterPosition)) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return pickerAdapter.moveItem(viewHolder.adapterPosition, target.adapterPosition, this@ImageTagEditorActivity)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                pickerAdapter.syncTagsToManager(this@ImageTagEditorActivity)
            }
        })
        touchHelper.attachToRecyclerView(rvTags)

        btnAddTag.setOnClickListener {
            val input = EditText(this).apply {
                hint = "新しいタグを入力..."
                setTextColor(Color.WHITE)
            }
            AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("新しいタグを作成")
                .setView(input)
                .setPositiveButton("作成") { _, _ ->
                    val newTag = input.text.toString().trim()
                    if (newTag.isNotEmpty()) {
                        TagManager.addTag(this@ImageTagEditorActivity, newTag)
                        pickerAdapter.refreshItems(this)
                        Toast.makeText(this, "「$newTag」を追加しました。", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        btnSave.setOnClickListener {
            if (isBatchMode) {
                val urisToEdit = selectedUris ?: emptyList<String>()
                val tagsToAdd = selectedTags - initialEffectiveTags
                val tagsToRemove = initialEffectiveTags - selectedTags

                DataManager.allImages.forEach { entry ->
                    if (urisToEdit.contains(entry.uri.toString())) {
                        // 「統一規格」の自動登録を実行するわ（追加されたタグに対して）
                        TagManager.autoRegisterUniformImpliedTags(this, entry.tags, tagsToAdd)

                        val currentEffective = TagManager.getEffectiveTags(entry.tags).toMutableSet()
                        currentEffective.addAll(tagsToAdd)
                        currentEffective.removeAll(tagsToRemove)
                        
                        val minimized = TagManager.minimizeTags(currentEffective)
                        entry.tags.clear()
                        entry.tags.addAll(minimized)
                    }
                }
                DataManager.saveData(this)
            } else if (isImageMode) {
                val tagsToAdd = selectedTags - initialEffectiveTags
                // 「統一規格」の自動登録
                TagManager.autoRegisterUniformImpliedTags(this, imageEntry!!.tags, tagsToAdd)

                val minimized = TagManager.minimizeTags(selectedTags)
                imageEntry!!.tags.clear()
                imageEntry!!.tags.addAll(minimized)
                DataManager.saveData(this)
            } else {
                var newName = nameInput?.text?.toString()?.trim() ?: ""
                val isAutoGeneratedNow = newName.isEmpty()
                
                if (isAutoGeneratedNow) {
                    newName = generateAutoName(radioGroup)
                }
                
                if (newName.isEmpty()) {
                    Toast.makeText(this, "セット名を入力するか、タグを一つ以上選んでね！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val currentMatchMode = if (radioGroup?.checkedRadioButtonId == radioGroup?.getChildAt(1)?.id) MatchMode.ALL else MatchMode.ANY

                val selectedUsage = when (usageRadioGroup?.checkedRadioButtonId) {
                    usageRadioGroup?.getChildAt(1)?.id -> ImageSetUsage.HOMESCREEN
                    usageRadioGroup?.getChildAt(2)?.id -> ImageSetUsage.CHAT
                    else -> ImageSetUsage.BOTH
                }

                val tempSet = ImageSet(name = newName, matchMode = currentMatchMode, usage = selectedUsage).apply {
                    targetTags.addAll(selectedTags)
                }
                val matchedImages = tempSet.filterImages(DataManager.allImages)
                if (matchedImages.isEmpty()) {
                    Toast.makeText(this, "警告: この条件だと画像が0枚になるわよ！タグを見直してね", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val oldName = intent.getStringExtra("SET_NAME") ?: ""

                if ((isNewSetMode || oldName != newName) && DataManager.imageSetList.any { it.name == newName }) {
                    Toast.makeText(this, "「$newName」はもうあるみたいよ。別の名前にしてみて！", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (isNewSetMode) {
                    imageSet!!.name = newName
                    imageSet!!.matchMode = currentMatchMode
                    imageSet!!.targetTags.clear()
                    imageSet!!.targetTags.addAll(selectedTags)
                    imageSet!!.isAutoGenerated = isAutoGeneratedNow
                    imageSet!!.isActive = cbActive?.isChecked ?: true
                    imageSet!!.usage = selectedUsage
                    DataManager.imageSetList.add(imageSet!!)
                } else {
                    imageSet!!.name = newName
                    imageSet!!.matchMode = currentMatchMode
                    imageSet!!.targetTags.clear()
                    imageSet!!.targetTags.addAll(selectedTags)
                    imageSet!!.isAutoGenerated = isAutoGeneratedNow
                    imageSet!!.isActive = cbActive?.isChecked ?: true
                    imageSet!!.usage = selectedUsage
                }
                
                DataManager.saveData(this)

                val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                
                if (settingsPrefs.getString("active_album_name", "") == oldName) {
                    val oldIndex = settingsPrefs.getInt("last_index_for_album_$oldName", 0)
                    settingsPrefs.edit()
                        .putString("active_album_name", newName)
                        .putInt("last_index_for_album_$newName", oldIndex)
                        .apply()
                }

                if (settingsPrefs.getString("active_album_name_homescreen", "") == oldName) {
                    val oldIndex = settingsPrefs.getInt("last_index_for_album_$oldName", 0)
                    settingsPrefs.edit()
                        .putString("active_album_name_homescreen", newName)
                        .putInt("last_index_for_album_$newName", oldIndex)
                        .apply()
                }

                if (settingsPrefs.getString("active_album_name_chat", "") == oldName) {
                    val oldIndex = settingsPrefs.getInt("last_index_for_album_$oldName", 0)
                    settingsPrefs.edit()
                        .putString("active_album_name_chat", newName)
                        .putInt("last_index_for_album_$newName", oldIndex)
                        .apply()
                }
            }
            
            sendBroadcast(Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED"))
            val successMsg = when {
                isBatchMode -> "${selectedUris?.size}枚の属性を一括更新したわよ！"
                isNewSetMode -> "新しいセットを作ったわ！"
                else -> "保存しました！"
            }
            Toast.makeText(this, successMsg, Toast.LENGTH_SHORT).show()
            finish()
        }

        btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun generateAutoName(radioGroup: RadioGroup?): String {
        if (selectedTags.isEmpty()) return ""
        val joiner = if (radioGroup?.checkedRadioButtonId == radioGroup?.getChildAt(1)?.id) "＋" else " または "
        
        val allTagsOrdered = TagManager.categories.flatMap { it.tags }
        val priorityMap = allTagsOrdered.withIndex().associate { it.value to it.index }
        val sortedTags = selectedTags.toList().sortedBy { priorityMap[it] ?: Int.MAX_VALUE }
        
        return sortedTags.map { "#$it" }.joinToString(joiner)
    }

    private fun updateAutoName(nameInput: EditText?, radioGroup: RadioGroup?) {
        if (nameInput == null) return
        val autoName = generateAutoName(radioGroup)
        if (autoName.isNotEmpty()) {
            nameInput.hint = "例: $autoName"
        } else {
            nameInput.hint = "セット名を入力...（空欄なら自動生成）"
        }
    }

    private fun showDirectDescriptionDialog(entry: ImageEntry) {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.activity_tag_prompt_editor, null)
        
        val etDesc = dialogView.findViewById<EditText>(R.id.et_prompt_input)
        val tvDialogTitle = dialogView.findViewById<TextView>(R.id.tv_editor_title)
        val etTagName = dialogView.findViewById<EditText>(R.id.et_tag_name)
        val btnAi = dialogView.findViewById<Button>(R.id.btn_ai_generate)
        val btnSave = dialogView.findViewById<Button>(R.id.btn_save)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel)
        val tvCounter = dialogView.findViewById<TextView>(R.id.tv_counter)

        // 不要な要素を完全に消去するわよ！
        dialogView.findViewById<View>(R.id.btn_edit_implied_tags)?.parent?.let { (it as? View)?.visibility = View.GONE }
        dialogView.findViewById<View>(R.id.btn_edit_implied_tags)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn_migrate)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn_delete)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_implied_tags_display)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btn_link_local_card)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.tv_local_card_status)?.visibility = View.GONE
        etTagName?.visibility = View.GONE
        // 「タグ名」のラベルも消しちゃうわ
        (etTagName?.parent as? LinearLayout)?.let { parent ->
            val idx = parent.indexOfChild(etTagName)
            if (idx > 0) parent.getChildAt(idx - 1).visibility = View.GONE
        }

        // --- AI設定セクションの追加 ---
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val aiConfigLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }

        // プロファイル選択
        val profilesJson = prefs.getString("ai_system_prompt_profiles", null)
        val profiles = mutableListOf<JSONObject>()
        if (profilesJson != null) {
            val arr = JSONArray(profilesJson)
            for (i in 0 until arr.length()) profiles.add(arr.getJSONObject(i))
        }
        if (profiles.isEmpty()) {
            profiles.add(JSONObject().apply { 
                put("name", "標準説明生成")
                put("content", "あなたはAIキャラクターチャットのコンテキスト設計におけるスペシャリストです。渡された画像のタグ情報を元に、そのキャラクターの見た目、性格、設定、またはこの画像が示すシチュエーションを300文字から500文字以内で詳しく説明してください。出力は純粋な説明文のみにしてください。") 
            })
        }

        var selectedSystemPrompt = profiles.first().getString("content")

        val btnProfile = Button(this).apply {
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            text = "プロファイル: ${profiles.first().getString("name")}"
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 12f
            setOnClickListener {
                val names = profiles.map { it.getString("name") }.toTypedArray()
                AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("AI指示プロファイル選択")
                    .setItems(names) { _, which ->
                        val selected = profiles[which]
                        text = "プロファイル: ${selected.getString("name")}"
                        selectedSystemPrompt = selected.getString("content")
                    }.show()
            }
        }
        aiConfigLayout.addView(btnProfile)

        val btnProvider = Button(this).apply {
            // スタイルは直接指定できないから、見た目を整えるわよ
            background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_persona_item)
            val current = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
            text = "プロバイダー: $current"
            setTextColor(Color.parseColor("#8AB4F8"))
            textSize = 12f
            setOnClickListener {
                val providers = arrayOf("GROK", "OPENROUTER")
                AlertDialog.Builder(context, R.style.Theme_Kennys_dokidoki_wallpaper)
                    .setTitle("AIプロバイダー選択")
                    .setItems(providers) { _, which ->
                        val selected = providers[which]
                        text = "プロバイダー: $selected"
                        prefs.edit().putString("chat_cloud_provider", selected).apply()
                    }.show()
            }
        }
        aiConfigLayout.addView(btnProvider)

        val tvStatus = TextView(this).apply {
            text = "AI生成の準備完了"
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        aiConfigLayout.addView(tvStatus)

        // ボタンの直前に設定をねじ込むわ
        (btnAi?.parent as? LinearLayout)?.addView(aiConfigLayout, (btnAi.parent as LinearLayout).indexOfChild(btnAi))

        tvDialogTitle?.text = "画像個別プロンプト"
        etDesc?.setText(TavernCardParser.cleanDescriptionText(entry.description))
        etDesc?.hint = "この画像自体の説明や、チャットでの振る舞いを入力してね。"
        
        val dialog = AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setView(dialogView)
            .create()

        fun updateCounter(text: String) {
            val chars = text.length
            val tokens = TagManager.estimateTokenCount(text)
            tvCounter?.text = "$chars 文字 | 約 $tokens トークン"
        }
        etDesc?.text?.toString()?.let { updateCounter(it) }

        etDesc?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateCounter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnAi?.setOnClickListener {
            generateDescriptionAi(entry, etDesc ?: return@setOnClickListener, tvStatus, selectedSystemPrompt)
        }

        btnSave?.setOnClickListener {
            val originalMeta = TavernCardParser.getTavernMeta(entry.description)
            val newText = etDesc?.text?.toString()?.trim() ?: ""
            if (newText.isEmpty()) {
                entry.description = null
            } else {
                if (originalMeta != null) {
                    entry.description = "$newText\n\n<TavernMeta>\n$originalMeta\n</TavernMeta>"
                } else {
                    entry.description = newText
                }
            }
            DataManager.saveData(this)
            Toast.makeText(this, "設定を反映させたわよ！", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnCancel?.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun generateDescriptionAi(entry: ImageEntry, targetEditText: EditText, statusTextView: TextView, systemPrompt: String) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
        
        statusTextView.text = "AIが思考中...（プロンプト構築中）"
        statusTextView.setTextColor(Color.parseColor("#FDD663"))

        coroutineScope.launch(Dispatchers.IO) {
            try {
                var reply = ""
                val effectiveTags = TagManager.getEffectiveTags(selectedTags).joinToString(", ")
                val userText = "現在のタグ: $effectiveTags\n指示: この画像に相応しい詳細なキャラクター説明文を作成して。"

                withContext(Dispatchers.Main) { statusTextView.text = "画像を変換中..." }
                val imageBase64 = encodeImageToBase64(entry.uri)
                
                withContext(Dispatchers.Main) { statusTextView.text = "APIリクエスト送信中 ($provider)..." }

                if (provider == "OPENROUTER") {
                    val apiKey = OpenRouterManager.getActiveApiKey(this@ImageTagEditorActivity)
                    if (apiKey == null) {
                        throw Exception("OpenRouterのAPI Keyが設定されてないわよ！")
                    }
                    val modelName = prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free") ?: "deepseek/deepseek-v4-flash:free"
                    withContext(Dispatchers.Main) { statusTextView.text = "OpenRouter応答待機中 ($modelName)..." }

                    val url = URL("https://openrouter.ai/api/v1/chat/completions")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 30000
                        readTimeout = 30000
                        doOutput = true
                    }
                    
                    val userContent = JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", userText) })
                        if (imageBase64 != null) {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        }
                    }

                    val messages = JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    }
                    val requestBody = JSONObject().apply {
                        put("model", modelName)
                        put("messages", messages)
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
                    
                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        reply = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        OpenRouterManager.incrementUsage(this@ImageTagEditorActivity, apiKey)
                    } else {
                        val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                        throw Exception("OpenRouter Error: $error")
                    }
                    conn.disconnect()
                } else {
                    val xaiApiKey = prefs.getString("xai_api_key", "")?.trim() ?: ""
                    if (xaiApiKey.isEmpty()) {
                        throw Exception("xAI (Grok) のAPI Keyが設定されてないわよ！")
                    }
                    withContext(Dispatchers.Main) { statusTextView.text = "Grok応答待機中..." }
                    
                    val apiKey = if (xaiApiKey.startsWith("xai-")) xaiApiKey else "xai-$xaiApiKey"
                    val url = URL("https://api.x.ai/v1/chat/completions")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 30000
                        readTimeout = 30000
                        doOutput = true
                    }
                    
                    val userContent = JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", userText) })
                        if (imageBase64 != null) {
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$imageBase64")
                                })
                            })
                        }
                    }

                    val messages = JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", userContent) })
                    }
                    val requestBody = JSONObject().apply {
                        put("model", "grok-4-1-fast-non-reasoning")
                        put("messages", messages)
                    }
                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }
                    
                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        reply = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                    } else {
                        val error = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${conn.responseCode}"
                        throw Exception("Grok Error: $error")
                    }
                    conn.disconnect()
                }

                if (reply.isNotEmpty()) {
                    withContext(Dispatchers.Main) { 
                        targetEditText.setText(reply)
                        statusTextView.text = "生成成功！✨"
                        statusTextView.setTextColor(Color.parseColor("#81C995"))
                        Toast.makeText(this@ImageTagEditorActivity, "AIが素晴らしい説明を考えてくれたわ！", Toast.LENGTH_SHORT).show() 
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    statusTextView.text = "エラー: ${e.message}"
                    statusTextView.setTextColor(Color.parseColor("#F28B82"))
                    Toast.makeText(this@ImageTagEditorActivity, "AIがちょっと迷子になっちゃったみたい...", Toast.LENGTH_SHORT).show() 
                }
            }
        }
    }

    private fun encodeImageToBase64(uri: Uri): String? {
        return try {
            val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }
}
