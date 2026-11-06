package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import org.json.JSONTokener
import java.util.concurrent.Executors

class ThreeJsManagerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var countText: TextView
    private lateinit var emptyStateText: TextView
    private lateinit var adapter: WorkGalleryAdapter
    private var thumbCapturer: ThreeJsThumbnailCapturer? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importDeviceJsonUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = GALLERY_BG_COLOR
        window.navigationBarColor = GALLERY_BG_COLOR
        setContentView(R.layout.activity_threejs_manager)

        recyclerView = findViewById(R.id.recycler_threejs_works)
        countText = findViewById(R.id.txt_threejs_work_count)
        emptyStateText = findViewById(R.id.txt_threejs_empty_state)
        val capturerHost = findViewById<FrameLayout>(R.id.threejs_offscreen_capturer_host)

        val btnBack = findViewById<ImageButton>(R.id.btn_threejs_back)
        val btnImportFile = findViewById<TextView>(R.id.btn_threejs_import_file)
        val btnOpenBrowser = findViewById<TextView>(R.id.btn_threejs_open_browser)
        val btnPowerSettings = findViewById<TextView>(R.id.btn_threejs_power_settings)
        val btnOverflow = findViewById<ImageButton>(R.id.btn_threejs_overflow)

        styleActionPill(btnImportFile)
        styleActionPill(btnOpenBrowser)
        styleActionPill(btnPowerSettings)

        btnBack.setOnClickListener { finish() }
        btnImportFile.setOnClickListener { launchDeviceJsonPicker() }
        btnOpenBrowser.setOnClickListener {
            startActivity(Intent(this, ThreeJsBrowserActivity::class.java))
        }
        btnPowerSettings.setOnClickListener {
            startActivity(Intent(this, ThreeJsPowerSettingsActivity::class.java))
        }
        btnOverflow.setOnClickListener { anchor -> showHeaderMenu(anchor) }

        adapter = WorkGalleryAdapter { work -> showWorkDetailDialog(work) }
        recyclerView.layoutManager = GridLayoutManager(this, resolveGridColumns())
        recyclerView.adapter = adapter

        thumbCapturer = ThreeJsThumbnailCapturer(
            context = this,
            hostContainer = capturerHost,
            onThumbSaved = { workId -> adapter.notifyThumbUpdated(workId) }
        )
    }

    override fun onResume() {
        super.onResume()
        reloadGallery()
    }

    override fun onDestroy() {
        thumbCapturer?.release()
        thumbCapturer = null
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun resolveGridColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        if (widthDp >= WIDE_GRID_BREAKPOINT_DP) {
            return WIDE_GRID_COLUMNS
        }
        return COMPACT_GRID_COLUMNS
    }

    private fun styleActionPill(view: TextView) {
        val density = resources.displayMetrics.density
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = PILL_RADIUS_DP * density
            setColor(PILL_BG_COLOR)
            setStroke((1f * density).toInt().coerceAtLeast(1), PILL_STROKE_COLOR)
        }
    }

    private fun reloadGallery() {
        val works = ThreeJsWorkStore.loadWorks(this)
        val preset = ThreeJsPowerPolicy.detectPreset(ThreeJsPowerStore.loadConfig(this))
        adapter.submitWorks(works)
        countText.text = "${works.size} works · ${preset.badge}"
        if (works.isEmpty()) {
            emptyStateText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyStateText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            thumbCapturer?.enqueueMissing(works)
        }
    }

    private fun showHeaderMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_POWER_SETTINGS, 0, "省電力・動作設定")
        popup.menu.add(0, MENU_PURGE_CACHES, 1, "キャッシュ・メモリを今すぐ解放")
        popup.menu.add(0, MENU_REGENERATE_THUMBS, 2, "サムネイルを再撮影")
        if (ThreeJsWorkStore.hasDeletedBuiltIns(this)) {
            popup.menu.add(0, MENU_RESTORE_BUILTINS, 3, "初期作品を復元")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_POWER_SETTINGS -> {
                    startActivity(Intent(this, ThreeJsPowerSettingsActivity::class.java))
                    true
                }
                MENU_PURGE_CACHES -> {
                    ThreeJsPowerStore.requestImmediatePurge(this)
                    Toast.makeText(this, "three.js のメモリ・キャッシュを解放しました", Toast.LENGTH_SHORT).show()
                    true
                }
                MENU_REGENERATE_THUMBS -> {
                    ThreeJsWorkStore.clearAllThumbnails(this)
                    reloadGallery()
                    Toast.makeText(this, "サムネイルを再生成しています", Toast.LENGTH_SHORT).show()
                    true
                }
                MENU_RESTORE_BUILTINS -> {
                    val count = ThreeJsWorkStore.restoreBuiltIns(this)
                    reloadGallery()
                    notifyWallpaperChanged()
                    Toast.makeText(this, "${count}件の初期作品を復元しました", Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun launchDeviceJsonPicker() {
        openJsonLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
    }

    private fun importDeviceJsonUri(uri: Uri) {
        val fileStem = resolveDisplayNameStem(uri)
        ioExecutor.execute {
            val rawText = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull()

            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (rawText.isNullOrBlank()) {
                    Toast.makeText(this, "JSON ファイルを読み込めませんでした", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val inspection = ThreeJsInspectorPolicy.inspectJsonText(rawText, fileStem)
                if (!inspection.kind.isValidJson()) {
                    Toast.makeText(this, inspection.summaryText, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                promptImportTitleAndSave(
                    defaultTitle = fileStem.ifEmpty { inspection.suggestedTitle },
                    inspection = inspection,
                    rawJson = rawText
                )
            }
        }
    }

    private fun resolveDisplayNameStem(uri: Uri): String {
        var displayName = ""
        val cursor: Cursor? = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()
        cursor?.use { c ->
            if (c.moveToFirst()) {
                displayName = c.getString(0).orEmpty()
            }
        }
        if (displayName.isEmpty()) {
            displayName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
        return displayName.removeSuffix(".json").removeSuffix(".JSON").trim()
    }

    private fun promptImportTitleAndSave(
        defaultTitle: String,
        inspection: JsonInspectionResult,
        rawJson: String
    ) {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, (4 * density).toInt())
        }
        val infoLabel = TextView(this).apply {
            text = inspection.summaryText
            textSize = 12f
            setTextColor( Color.parseColor("#9E9EAA") )
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        val input = EditText(this).apply {
            setText(defaultTitle)
            hint = "作品名を入力"
            setSingleLine(true)
            setSelection(text.length)
        }
        container.addView(infoLabel)
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("three.js 作品を追加")
            .setView(container)
            .setPositiveButton("追加") { _, _ ->
                val title = input.text?.toString().orEmpty()
                val res = ThreeJsWorkStore.addUserWork(this, title, rawJson)
                res.onSuccess { added ->
                    reloadGallery()
                    thumbCapturer?.enqueueSingle(added.id)
                    notifyWallpaperChanged()
                    Toast.makeText(this, "「${added.title}」を追加しました", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this, err.message ?: "追加に失敗しました", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWorkDetailDialog(work: ThreeJsWork) {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DIALOG_BG_COLOR)
            val pad = (18 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val headerText = TextView(this).apply {
            text = ThreeJsPolicy.formatTileCaption(1, work)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#ECECF4"))
        }
        val originText = TextView(this).apply {
            text = if (work.origin == ThreeJsWorkOrigin.BUILT_IN) {
                "内蔵作品 · ID: ${work.id}"
            } else {
                "ユーザー追加作品 · ID: ${work.id}"
            }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#767682"))
            setPadding(0, (2 * density).toInt(), 0, (12 * density).toInt())
        }

        val previewSidePx = (240 * density).toInt()
        val previewHolder = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(previewSidePx, previewSidePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setBackgroundColor(Color.parseColor("#050507"))
        }

        val previewWebView = buildLivePreviewWebView(work.id)
        if (previewWebView != null) {
            previewHolder.addView(
                previewWebView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        root.addView(headerText)
        root.addView(originText)
        root.addView(previewHolder)

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .create()

        val btnCaptureFrame = createDialogActionButton("現在のプレビューをサムネイル保存", "#C8C8D6")
        val btnRename = createDialogActionButton("作品名を編集", "#E6E6F0")
        val btnEditJson = createDialogActionButton("JSON を編集", "#E6E6F0")
        val btnDelete = createDialogActionButton("この作品を削除", "#F29B96")
        val btnClose = createDialogActionButton("閉じる", "#8A8A96")

        btnCaptureFrame.setOnClickListener {
            capturePreviewFrame(previewWebView, work.id)
        }
        btnRename.setOnClickListener {
            dialog.dismiss()
            showRenameDialog(work)
        }
        btnEditJson.setOnClickListener {
            dialog.dismiss()
            openJsonEditorForWork(work)
        }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteWork(work)
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        root.addView(btnCaptureFrame)
        root.addView(btnRename)
        root.addView(btnEditJson)
        root.addView(btnDelete)
        root.addView(btnClose)

        dialog.setOnDismissListener {
            destroyPreviewWebView(previewWebView)
        }
        dialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLivePreviewWebView(workId: String): WebView? {
        val safeId = workId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safeId.isEmpty()) {
            return null
        }
        val view = runCatching { WebView(this) }.getOrNull() ?: return null
        view.setBackgroundColor(Color.BLACK)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return ThreeJsAssetHost.interceptRequest(applicationContext, request)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(
                    "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.activateWork('$safeId');",
                    null
                )
            }
        }
        view.loadUrl(ThreeJsCatalog.CAPTURE_URL)
        return view
    }

    private fun destroyPreviewWebView(view: WebView?) {
        if (view == null) {
            return
        }
        runCatching {
            view.evaluateJavascript(
                "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.stop();",
                null
            )
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    private fun capturePreviewFrame(previewWebView: WebView?, workId: String) {
        val view = previewWebView ?: return
        view.evaluateJavascript(
            "window.ThreeWallpaperPlayer ? window.ThreeWallpaperPlayer.captureCurrentFrame() : '';"
        ) { jsReturn ->
            val unquoted = decodeJsReturnedString(jsReturn)
            if (unquoted.startsWith("data:image/")) {
                val ok = ThreeJsWorkStore.saveThumbnailDataUrl(applicationContext, workId, unquoted)
                if (ok) {
                    adapter.notifyThumbUpdated(workId)
                    Toast.makeText(this, "サムネイルを更新しました", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun decodeJsReturnedString(rawJsValue: String?): String {
        if (rawJsValue.isNullOrBlank() || rawJsValue == "null") {
            return ""
        }
        return runCatching { JSONTokener(rawJsValue).nextValue()?.toString().orEmpty() }
            .getOrDefault("")
    }

    private fun createDialogActionButton(label: String, hexColor: String): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(hexColor))
            val padV = (10 * density).toInt()
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = PILL_RADIUS_DP * density
                setColor(PILL_BG_COLOR)
                setStroke((1f * density).toInt().coerceAtLeast(1), PILL_STROKE_COLOR)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }
    }

    private fun showRenameDialog(work: ThreeJsWork) {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(pad, (12 * density).toInt(), pad, 0)
        }
        val edit = EditText(this).apply {
            setText(work.title)
            setSingleLine(true)
            setSelection(text.length)
        }
        holder.addView(edit)

        AlertDialog.Builder(this)
            .setTitle("作品名を編集")
            .setView(holder)
            .setPositiveButton("保存") { _, _ ->
                val updated = ThreeJsWorkStore.renameWork(this, work.id, edit.text.toString())
                if (updated != null) {
                    reloadGallery()
                    notifyWallpaperChanged()
                } else {
                    Toast.makeText(this, "作品名を入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openJsonEditorForWork(work: ThreeJsWork) {
        ioExecutor.execute {
            val rawJson = ThreeJsWorkStore.readWorkJsonText(this, work.id)
            if (rawJson == null) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "JSON の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }
            val compacted = ThreeJsInspectorPolicy.compactForEditor(rawJson)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    showCompactedJsonDialog(work, compacted)
                }
            }
        }
    }

    private fun showCompactedJsonDialog(work: ThreeJsWork, doc: CompactedJsonDoc) {
        val density = resources.displayMetrics.density
        val pad = (14 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DIALOG_BG_COLOR)
            setPadding(pad, pad, pad, pad)
        }

        val statusBanner = TextView(this).apply {
            text = if (doc.placeholders.isNotEmpty()) {
                "大容量データ ${doc.placeholders.size} 件を短縮表示中（保存時に自動復元）"
            } else {
                "JSON ソース編集: ${work.title}"
            }
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#9A9AA8"))
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        val editor = EditText(this).apply {
            setText(doc.displayText)
            typeface = Typeface.MONOSPACE
            textSize = 11.5f
            setTextColor(Color.parseColor("#E4E4EE"))
            setBackgroundColor(Color.parseColor("#08080B"))
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            val innerPad = (10 * density).toInt()
            setPadding(innerPad, innerPad, innerPad, innerPad)
        }

        val editorHeightPx = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                editorHeightPx
            )
            addView(
                editor,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(statusBanner)
        root.addView(scroll)

        val dialog = AlertDialog.Builder(this)
            .setTitle("JSON を編集 (${work.title})")
            .setView(root)
            .setPositiveButton("保存", null)
            .setNeutralButton("構文検証", null)
            .setNegativeButton("キャンセル", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val expanded = ThreeJsInspectorPolicy.expandFromEditor(
                    editor.text.toString(),
                    doc
                )
                val check = ThreeJsInspectorPolicy.inspectJsonText(expanded)
                statusBanner.text = check.summaryText
                statusBanner.setTextColor(
                    if (check.kind.isValidJson()) {
                        Color.parseColor("#8CE0A8")
                    } else {
                        Color.parseColor("#F29B96")
                    }
                )
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val expanded = ThreeJsInspectorPolicy.expandFromEditor(
                    editor.text.toString(),
                    doc
                )
                val res = ThreeJsWorkStore.updateWorkJson(this, work.id, expanded)
                res.onSuccess {
                    dialog.dismiss()
                    reloadGallery()
                    thumbCapturer?.enqueueSingle(work.id)
                    notifyWallpaperChanged()
                    Toast.makeText(this, "JSON を保存しました", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    statusBanner.text = err.message ?: "JSON 構文エラー"
                    statusBanner.setTextColor(Color.parseColor("#F29B96"))
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteWork(work: ThreeJsWork) {
        AlertDialog.Builder(this)
            .setTitle("作品を削除")
            .setMessage("「${work.title}」を一覧から削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                if (ThreeJsWorkStore.deleteWork(this, work.id)) {
                    reloadGallery()
                    notifyWallpaperChanged()
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun notifyWallpaperChanged() {
        val intent = Intent(ACTION_WALLPAPER_CHANGED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private inner class WorkGalleryAdapter(
        private val onWorkClick: (ThreeJsWork) -> Unit
    ) : RecyclerView.Adapter<WorkGalleryAdapter.TileHolder>() {

        private val items = mutableListOf<ThreeJsWork>()

        fun submitWorks(newWorks: List<ThreeJsWork>) {
            items.clear()
            items.addAll(newWorks)
            notifyDataSetChanged()
        }

        fun notifyThumbUpdated(workId: String) {
            val idx = items.indexOfFirst { it.id == workId }
            if (idx >= 0) {
                notifyItemChanged(idx)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_threejs_work_tile, parent, false)
            return TileHolder(view)
        }

        override fun getItemCount(): Int {
            return items.size
        }

        override fun onBindViewHolder(holder: TileHolder, position: Int) {
            val work = items[position]
            holder.caption.text = ThreeJsPolicy.formatTileCaption(position + 1, work)

            val thumbFile = ThreeJsWorkStore.thumbnailFile(holder.itemView.context, work.id)
            if (thumbFile.isFile && thumbFile.length() > 0L) {
                holder.renderingHint.visibility = View.GONE
                Glide.with(holder.thumb.context)
                    .load(thumbFile)
                    .signature(ObjectKey(thumbFile.lastModified()))
                    .into(holder.thumb)
            } else {
                Glide.with(holder.thumb.context).clear(holder.thumb)
                holder.thumb.setImageDrawable(null)
                holder.renderingHint.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener { onWorkClick(work) }
        }

        inner class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.threejs_tile_thumb)
            val renderingHint: TextView = view.findViewById(R.id.threejs_tile_rendering_hint)
            val caption: TextView = view.findViewById(R.id.threejs_tile_caption)
        }
    }

    companion object {
        private const val ACTION_WALLPAPER_CHANGED =
            "com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED"
        private const val GALLERY_BG_COLOR = 0xFF0B0B0D.toInt()
        private const val DIALOG_BG_COLOR = 0xFF121216.toInt()
        private const val PILL_BG_COLOR = 0xFF15151B.toInt()
        private const val PILL_STROKE_COLOR = 0xFF2B2B35.toInt()
        private const val PILL_RADIUS_DP = 6f
        private const val WIDE_GRID_BREAKPOINT_DP = 560
        private const val COMPACT_GRID_COLUMNS = 2
        private const val WIDE_GRID_COLUMNS = 3
        private const val MENU_REGENERATE_THUMBS = 1
        private const val MENU_RESTORE_BUILTINS = 2
        private const val MENU_POWER_SETTINGS = 3
        private const val MENU_PURGE_CACHES = 4
    }
}
