package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
    private lateinit var btnImport: MaterialButton
    private lateinit var progress: LinearProgressIndicator
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

    companion object {
        const val EXTRA_MODEL_ID = "MODEL_ID"
        const val EXTRA_VERSION_ID = "VERSION_ID"
        private const val THUMB_MAX_SIDE = 768
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
        btnImport = findViewById(R.id.btn_import)
        progress = findViewById(R.id.progress_import)
        tvStatus = findViewById(R.id.tv_import_status)

        rvShowcase.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        rvShowcase.adapter = showcaseAdapter
        bindCategoryDropdown()

        btnCrop.setOnClickListener { startCrop() }
        btnImport.setOnClickListener { startImport() }

        val modelId = intent.getLongExtra(EXTRA_MODEL_ID, 0L)
        if (modelId <= 0L) {
            Toast.makeText(this, "モデルIDが不正です。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        fetchAll(modelId, intent.getLongExtra(EXTRA_VERSION_ID, 0L))
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
        etLabel.setText(fetched.name)

        val names = fetched.versions.map { versionName(it) }
        val initial = fetched.versions.indexOfFirst { it.id == versionId }.takeIf { it >= 0 } ?: 0
        bindDropdown(actVersions, names, names[initial])
        actVersions.setOnItemClickListener { _, _, position, _ ->
            selectVersion(fetched.versions[position])
        }
        selectVersion(fetched.versions[initial])
        btnImport.isEnabled = true
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
        btnImport.isEnabled = file != null

        val images = version.images.filter { it.url.isNotBlank() }
        val preselect = images.firstOrNull { it.prompt.isNotBlank() } ?: images.firstOrNull()
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
        val fetched = model ?: return
        val version = selectedVersion ?: return
        val file = selectedFile ?: return
        val label = etLabel.text?.toString()?.trim().orEmpty()
        val mainPrompt = etMainPrompt.text?.toString()?.trim().orEmpty()
        if (label.isEmpty()) {
            Toast.makeText(this, "カード名を入力してね", Toast.LENGTH_SHORT).show()
            return
        }
        if (mainPrompt.isEmpty()) {
            Toast.makeText(this, "メインプロンプトを入力してね", Toast.LENGTH_SHORT).show()
            return
        }
        if (pollJob?.isActive == true) {
            return
        }
        btnImport.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        showStatus("PCへ送信中...")

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
                    thumbnailBase64 = thumbB64
                )
                var state = GenerationAgentClient.submitModelImport(this@CivitaiImportActivity, request)
                while (!state.isTerminal) {
                    renderImportState(state)
                    delay(1500)
                    state = GenerationAgentClient.getModelImport(this@CivitaiImportActivity, state.id)
                }
                renderImportState(state)
                if (!state.isOk) {
                    throw IllegalStateException(state.error ?: "PCでの保存に失敗しました")
                }
                finalizeCard(label, mainPrompt, etNegativePrompt.text?.toString()?.trim().orEmpty())
            } catch (error: Exception) {
                progress.isIndeterminate = false
                progress.visibility = View.GONE
                showStatus("失敗: ${error.message}")
                btnImport.isEnabled = true
            }
        }
    }

    private fun renderImportState(state: ModelImportState) {
        if (state.total > 0L) {
            val pct = (state.progress * 100).toInt().coerceIn(0, 100)
            progress.isIndeterminate = false
            progress.setProgressCompat(pct, true)
            showStatus("PCでダウンロード中 $pct% (${mb(state.downloaded)}/${mb(state.total)})")
        } else {
            progress.isIndeterminate = true
            showStatus("PCでダウンロード中... (${mb(state.downloaded)})")
        }
    }

    private fun mb(bytes: Long): String {
        return "%.1fMB".format(bytes / 1024.0 / 1024.0)
    }

    private fun showStatus(text: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = text
    }

    private suspend fun finalizeCard(label: String, mainPrompt: String, negativePrompt: String) {
        showStatus("プロンプトカードを作成中...")
        val cardId = UUID.randomUUID().toString()
        val thumbUri = withContext(Dispatchers.IO) { saveCardThumb(cardId) }
        val category = actCategory.text?.toString()?.trim().ifNullOrBlank("未分類")
        val card = PromptCard(
            id = cardId,
            label = label,
            mainPrompt = mainPrompt,
            negativePrompt = negativePrompt,
            thumbnailUri = thumbUri,
            category = category
        )
        PromptCardManager.addCard(this, card)
        Toast.makeText(this, "インポート完了: $label", Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun saveCardThumb(cardId: String): android.net.Uri? {
        val cropped = croppedThumb?.takeIf { it.isFile }
        val bytes: ByteArray = try {
            when {
                cropped != null -> cropped.readBytes()
                selectedImage != null -> {
                    val connection = (java.net.URL(selectedImage!!.url).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "AndroidToolkits/1.0")
                        connectTimeout = 15000
                        readTimeout = 60000
                    }
                    try {
                        connection.inputStream.use { it.readBytes() }
                    } finally {
                        connection.disconnect()
                    }
                }
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMB_MAX_SIDE * 2 && sample < 16) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            }) ?: return null
            val scaled = scaleDown(decoded)
            val dir = File(filesDir, "civitai_thumbs").apply { mkdirs() }
            val out = File(dir, "$cardId.jpg")
            FileOutputStream(out).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            }
            if (scaled !== decoded) {
                decoded.recycle()
            }
            android.net.Uri.fromFile(out)
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= THUMB_MAX_SIDE) {
            return source
        }
        val ratio = THUMB_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun String?.ifNullOrBlank(default: String): String {
        return if (this.isNullOrBlank()) default else this
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
