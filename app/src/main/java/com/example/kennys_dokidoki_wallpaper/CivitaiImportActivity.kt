package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// MD3 import sheet: pick version + thumbnail, edit prompts, send to PC.
class CivitaiImportActivity : AppCompatActivity() {

    private lateinit var tvModelName: MaterialTextView
    private lateinit var chipKind: Chip
    private lateinit var tvTargetDir: MaterialTextView
    private lateinit var actVersions: AutoCompleteTextView
    private lateinit var tvFileName: MaterialTextView
    private lateinit var tvFileSize: MaterialTextView
    private lateinit var tvTriggerWords: MaterialTextView
    private lateinit var rvShowcase: RecyclerView
    private lateinit var ivPreview: ImageView
    private lateinit var btnCrop: MaterialButton
    private lateinit var etLabel: TextInputEditText
    private lateinit var actCategory: AutoCompleteTextView
    private lateinit var etMainPrompt: TextInputEditText
    private lateinit var etNegativePrompt: TextInputEditText
    private lateinit var etToken: TextInputEditText
    private lateinit var btnImport: CivitaiProgressButton
    private lateinit var tvStatus: MaterialTextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollJob: Job? = null
    private val showcaseAdapter = CivitaiShowcaseAdapter { onShowcasePick(it) }

    private var model: CivitaiApi.Model? = null
    private var kind = CivitaiApi.KIND_LORA
    private var selectedVersion: CivitaiApi.Version? = null
    private var selectedFile: CivitaiApi.ModelFile? = null
    private var selectedImage: CivitaiApi.ShowImage? = null
    private var croppedThumb: File? = null
    private var targetDir = ""
    private var openedModelId = 0L
    private var keepRestoredDraft = false

    companion object {
        const val EXTRA_MODEL_ID = "MODEL_ID"
        const val EXTRA_VERSION_ID = "VERSION_ID"
        private const val TOKEN_KEY = "civitai_api_token"
        private const val IDLE_TEXT = "PCにインポート"
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val path = result.data?.getStringExtra(CivitaiThumbnailCropActivity.EXTRA_CROPPED_PATH)
            if (!path.isNullOrBlank() && File(path).isFile) {
                croppedThumb = File(path)
                Glide.with(this).load(croppedThumb).into(ivPreview)
                Toast.makeText(this, "サムネイルを切り抜きました。", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_civitai_import)
        PromptCardManager.loadCards(this)

        findViewById<MaterialToolbar>(R.id.toolbar_import).setNavigationOnClickListener { finish() }
        tvModelName = findViewById(R.id.tv_model_name)
        chipKind = findViewById(R.id.chip_model_kind)
        tvTargetDir = findViewById(R.id.tv_target_dir)
        actVersions = findViewById(R.id.act_versions)
        tvFileName = findViewById(R.id.tv_file_name)
        tvFileSize = findViewById(R.id.tv_file_size)
        tvTriggerWords = findViewById(R.id.tv_trigger_words)
        rvShowcase = findViewById(R.id.rv_showcase)
        ivPreview = findViewById(R.id.iv_thumb_preview)
        btnCrop = findViewById(R.id.btn_crop_thumb)
        etLabel = findViewById(R.id.et_card_label)
        actCategory = findViewById(R.id.act_category)
        etMainPrompt = findViewById(R.id.et_main_prompt)
        etNegativePrompt = findViewById(R.id.et_negative_prompt)
        etToken = findViewById(R.id.et_civitai_token)
        btnImport = findViewById(R.id.btn_import)
        tvStatus = findViewById(R.id.tv_import_status)

        rvShowcase.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvShowcase.adapter = showcaseAdapter
        bindCategoryDropdown()
        etToken.setText(getSharedPreferences("settings", Context.MODE_PRIVATE).getString(TOKEN_KEY, ""))

        btnCrop.setOnClickListener { startCrop() }
        btnImport.setOnClickListener { startImport() }

        openedModelId = intent.getLongExtra(EXTRA_MODEL_ID, 0L)
        if (openedModelId <= 0L) {
            Toast.makeText(this, "モデルIDが不正です。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        restorePending()
        fetchAll(openedModelId, intent.getLongExtra(EXTRA_VERSION_ID, 0L))
    }

    // A killed app returns here: refill the draft and keep polling the PC job.
    private fun restorePending() {
        val pending = CivitaiImportStore.loadPending(this) ?: return
        if (pending.modelId != openedModelId) {
            showStatus("別のモデル（${pending.filename}）のインポートが進行中です。")
            return
        }
        keepRestoredDraft = true
        etLabel.setText(pending.label)
        etMainPrompt.setText(pending.mainPrompt)
        etNegativePrompt.setText(pending.negativePrompt)
        actCategory.setText(pending.category, false)
        pending.thumbPath?.let { File(it) }?.takeIf { it.isFile }?.let {
            croppedThumb = it
            Glide.with(this).load(it).into(ivPreview)
        }
        resumeImport(pending)
    }

    private fun bindCategoryDropdown() {
        val options = CategoryPickerPolicy.selectable(PromptCardManager.categoryOrder)
        bindDropdown(actCategory, options, CategoryPickerPolicy.defaultSelected(options, ""))
    }

    private fun bindDropdown(field: AutoCompleteTextView, options: List<String>, current: String) {
        field.threshold = Int.MAX_VALUE
        field.setAdapter(ArrayAdapter(field.context, android.R.layout.simple_list_item_1, options))
        field.setText(current, false)
        field.setOnClickListener { field.showDropDown() }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                field.showDropDown()
            }
        }
    }

    private fun fetchAll(modelId: Long, versionId: Long) {
        scope.launch {
            // PC health runs beside the Civitai fetch so a sleeping PC never blocks metadata.
            val healthJob = async(Dispatchers.IO) {
                try {
                    GenerationAgentClient.fetchHealth(this@CivitaiImportActivity)
                } catch (_: Exception) {
                    null
                }
            }
            val fetched = try {
                CivitaiApi.fetchModel(modelId)
            } catch (error: Exception) {
                healthJob.cancel()
                tvModelName.text = "取得に失敗しました"
                showStatus("Civitaiからの取得に失敗: ${error.message}")
                return@launch
            }
            val health = try {
                healthJob.await()
            } catch (_: Exception) {
                null
            }
            if (fetched.versions.isEmpty()) {
                tvModelName.text = fetched.name
                showStatus("このモデルにバージョン情報がありません。")
                return@launch
            }
            model = fetched
            kind = CivitaiApi.kindFor(fetched.type)
            targetDir = if (kind == CivitaiApi.KIND_CHECKPOINT) {
                health?.optString("checkpoint_dir").orEmpty()
            } else {
                health?.optString("lora_dir").orEmpty()
            }
            bindModel(fetched, versionId)
        }
    }

    private fun bindModel(fetched: CivitaiApi.Model, versionId: Long) {
        tvModelName.text = fetched.name
        chipKind.text = CivitaiApi.kindLabel(kind)
        tvTargetDir.text = targetDir.ifBlank { "PCの${CivitaiApi.kindLabel(kind)}フォルダ" }
        if (!keepRestoredDraft) {
            etLabel.setText(fetched.name)
        }

        val names = fetched.versions.map { versionName(it) }
        val initial = fetched.versions.indexOfFirst { it.id == versionId }.takeIf { it >= 0 } ?: 0
        bindDropdown(actVersions, names, names[initial])
        actVersions.setOnItemClickListener { _, _, position, _ ->
            selectVersion(fetched.versions[position])
        }
        selectVersion(fetched.versions[initial])
        if (pollJob?.isActive != true) {
            resetImportButton()
            btnImport.isEnabled = true
        }
    }

    private fun versionName(version: CivitaiApi.Version): String {
        return if (version.baseModel.isBlank()) version.name else "${version.name} (${version.baseModel})"
    }

    private fun selectVersion(version: CivitaiApi.Version) {
        selectedVersion = version
        selectedFile = CivitaiApi.primaryFile(version)
        val file = selectedFile
        tvFileName.text = file?.name ?: "ファイルなし"
        tvFileSize.text = if (file == null) {
            "-"
        } else {
            "${CivitaiApi.formatSize(file.sizeKB)} ・ ${file.format.ifBlank { file.type }}"
        }
        tvTriggerWords.text = version.trainedWords.ifEmpty { listOf("なし") }.joinToString(", ")
        if (pollJob?.isActive != true) {
            btnImport.isEnabled = file != null
        }

        val images = version.images.filter { it.url.isNotBlank() }
        val preselect = images.firstOrNull { it.prompt.isNotBlank() } ?: images.firstOrNull()
        if (keepRestoredDraft) {
            keepRestoredDraft = false
            showcaseAdapter.submit(images, null)
            if (croppedThumb == null) {
                selectedImage = preselect
                previewSelected()
            }
            return
        }
        selectedImage = preselect
        croppedThumb = null
        showcaseAdapter.submit(images, preselect)
        refreshPrompts()
        previewSelected()
    }

    private fun onShowcasePick(image: CivitaiApi.ShowImage) {
        selectedImage = image
        croppedThumb = null
        refreshPrompts()
        previewSelected()
    }

    private fun refreshPrompts() {
        val version = selectedVersion ?: return
        val file = selectedFile ?: return
        etMainPrompt.setText(
            CivitaiApi.buildMainPrompt(kind, file.name, version.trainedWords, selectedImage?.prompt.orEmpty())
        )
        etNegativePrompt.setText(selectedImage?.negativePrompt.orEmpty())
    }

    private fun previewSelected() {
        val image = selectedImage
        btnCrop.isEnabled = image != null
        if (image == null) {
            Glide.with(this).clear(ivPreview)
            ivPreview.setImageDrawable(null)
            return
        }
        Glide.with(this).load(image.url).into(ivPreview)
    }

    private fun startCrop() {
        val image = selectedImage ?: return
        btnCrop.isEnabled = false
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { CivitaiApi.downloadBytes(image.url) }
                val source = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "civitai").apply { mkdirs() }
                    File(dir, "source_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
                }
                cropLauncher.launch(
                    Intent(this@CivitaiImportActivity, CivitaiThumbnailCropActivity::class.java).apply {
                        putExtra(CivitaiThumbnailCropActivity.EXTRA_SOURCE_PATH, source.absolutePath)
                    }
                )
            } catch (error: Exception) {
                Toast.makeText(this@CivitaiImportActivity, "画像の取得に失敗: ${error.message}", Toast.LENGTH_SHORT).show()
            } finally {
                btnCrop.isEnabled = selectedImage != null
            }
        }
    }

    private fun startImport() {
        if (pollJob?.isActive == true) {
            return
        }
        CivitaiImportStore.loadPending(this)?.let { pending ->
            if (pending.modelId != openedModelId) {
                Toast.makeText(this, "別のモデルのインポートが進行中です。", Toast.LENGTH_SHORT).show()
                return
            }
            // Same model: refresh the draft with current edits, then keep polling.
            resumeImport(pending.copy(label = cardLabel(), mainPrompt = mainPrompt(), negativePrompt = negativePrompt(), category = cardCategory()))
            return
        }
        val fetched = model ?: return
        val version = selectedVersion ?: return
        val file = selectedFile ?: return
        val label = cardLabel()
        if (label.isEmpty()) {
            Toast.makeText(this, "カード名を入力してね", Toast.LENGTH_SHORT).show()
            return
        }
        if (mainPrompt().isEmpty()) {
            Toast.makeText(this, "メインプロンプトを入力してね", Toast.LENGTH_SHORT).show()
            return
        }
        val token = etToken.text?.toString()?.trim().orEmpty()
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, token).apply()

        setImportBusy("PCへ送信中...")
        pollJob = scope.launch {
            try {
                val thumbB64 = withContext(Dispatchers.IO) {
                    croppedThumb?.takeIf { it.isFile }?.readBytes()?.let {
                        Base64.encodeToString(it, Base64.NO_WRAP)
                    }
                }
                val request = ModelImportRequest(
                    downloadUrl = file.downloadUrl,
                    filename = file.name,
                    kind = kind,
                    modelName = fetched.name,
                    versionName = version.name,
                    triggerWords = version.trainedWords,
                    civitaiModelId = fetched.id,
                    civitaiVersionId = version.id,
                    thumbnailUrl = if (thumbB64 == null) selectedImage?.url?.takeIf { it.isNotBlank() } else null,
                    thumbnailBase64 = thumbB64,
                    civitaiToken = token.takeIf { it.isNotBlank() }
                )
                val accepted = GenerationAgentClient.submitModelImport(this@CivitaiImportActivity, request)
                val pending = PendingCivitaiImport(
                    jobId = accepted.id,
                    modelId = fetched.id,
                    versionId = version.id,
                    filename = file.name,
                    kind = kind,
                    label = label,
                    mainPrompt = mainPrompt(),
                    negativePrompt = negativePrompt(),
                    category = cardCategory(),
                    thumbPath = croppedThumb?.takeIf { it.isFile }?.absolutePath,
                    thumbUrl = selectedImage?.url?.takeIf { it.isNotBlank() }
                )
                CivitaiImportStore.savePending(this@CivitaiImportActivity, pending)
                pollPending(pending)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                resetImportButton()
                btnImport.isEnabled = true
                showStatus(friendlySubmitError(error.message))
            }
        }
    }

    private fun resumeImport(pending: PendingCivitaiImport) {
        if (pollJob?.isActive == true) {
            return
        }
        // Persist refreshed edits so a kill during polling still finalizes them.
        CivitaiImportStore.savePending(this, pending)
        setImportBusy("確認中...")
        pollJob = scope.launch { pollPending(pending) }
    }

    private suspend fun pollPending(pending: PendingCivitaiImport) {
        try {
            when (CivitaiImportFinalizer.awaitAndFinalize(this, pending, ::renderTick)) {
                true -> {
                    Toast.makeText(this, "インポート完了: ${pending.label}", Toast.LENGTH_LONG).show()
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                false -> {
                    resetImportButton()
                    btnImport.isEnabled = true
                    showStatus("失敗: ${CivitaiImportStore.takeLastError(this) ?: "PCでの保存に失敗しました"}")
                }
                null -> {
                    resetImportButton()
                    showStatus("バックグラウンドで確認中です。完了したら通知します。")
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            resetImportButton()
            btnImport.isEnabled = true
            showStatus("接続が切れました。開き直すと続きから確認します: ${error.message}")
        }
    }

    private fun renderTick(state: ModelImportState) {
        if (state.total > 0L) {
            val pct = (state.progress * 100).toInt().coerceIn(0, 100)
            btnImport.setFill(state.progress)
            btnImport.text = "インポート中 $pct%"
            showStatus("PCでダウンロード中 ${mb(state.downloaded)}/${mb(state.total)}")
        } else {
            btnImport.setFill(null)
            btnImport.text = "インポート中..."
            showStatus("PCでダウンロード中... (${mb(state.downloaded)})")
        }
    }

    private fun setImportBusy(text: String) {
        btnImport.isEnabled = false
        btnImport.setFill(null)
        btnImport.text = text
        showStatus(text)
    }

    private fun resetImportButton() {
        btnImport.setFill(null)
        btnImport.text = IDLE_TEXT
    }

    private fun friendlySubmitError(message: String?): String {
        val raw = message.orEmpty()
        if (raw.contains("HTTP 404")) {
            return "PCエージェントが古いままです。PC側を更新して再起動してください。"
        }
        return "失敗: $raw"
    }

    private fun mb(bytes: Long): String {
        return "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    private fun showStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
    }

    private fun cardLabel(): String = etLabel.text?.toString()?.trim().orEmpty()
    private fun mainPrompt(): String = etMainPrompt.text?.toString()?.trim().orEmpty()
    private fun negativePrompt(): String = etNegativePrompt.text?.toString()?.trim().orEmpty()
    private fun cardCategory(): String = actCategory.text?.toString()?.trim().orEmpty().ifEmpty { "未分類" }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
