package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.KeyListener
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 黒地に赤字の、古い個人サイト風の日記ページ。 */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var pageRoot: View
    private lateinit var miniGraph: DiaryContributionGraphView
    private lateinit var diaryEntryScroll: ScrollView
    private lateinit var entryFadeOverlay: View
    private lateinit var entryBottomFadeOverlay: View
    private lateinit var bottomMenu: LinearLayout
    private lateinit var selectedDateLabel: TextView
    private lateinit var diaryEditor: TextInputEditText
    private lateinit var diaryEditorLayout: TextInputLayout
    private lateinit var diaryEditorKeyListener: KeyListener
    private lateinit var emptyDiary: TextView
    private lateinit var photoCount: TextView
    private lateinit var editDiaryLink: TextView
    private lateinit var saveDiaryButton: TextView
    private lateinit var cancelDiaryButton: TextView
    private lateinit var addPhotoLink: TextView
    private lateinit var photoHint: TextView
    private lateinit var photoCanvas: FrameLayout
    private lateinit var photoLayer: DiaryPhotoLayer

    private var selectedDate: LocalDate = LocalDate.now(ZoneId.systemDefault())
    private var hasSelectedDate = false
    private var isEditingEntry = false
    private var editStartedFromEmptyHome = false
    private var isBindingDiaryText = false
    private var photoLayerReady = false
    private var currentEntry: DiaryEntry? = null
    private var savedEntry: DiaryEntry? = null
    private var palette: DiaryPalette = DiaryPalette(0xFF000000.toInt(), 0xFFFF2020.toInt())
    private var entryLengths: Map<String, Int> = emptyMap()

    private data class DiaryEditDraft(
        val original: DiaryEntry,
        val working: DiaryEntry,
        val startedFromEmptyHome: Boolean
    )

    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = true) }

    private val openBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = false) }

    private val pickDiaryImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> handleDiaryImageSelection(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        pageRoot = findViewById(R.id.diary_page_root)
        miniGraph = findViewById(R.id.diary_mini_graph)
        diaryEntryScroll = findViewById(R.id.diary_entry_scroll)
        entryFadeOverlay = findViewById(R.id.diary_entry_top_fade)
        entryBottomFadeOverlay = findViewById(R.id.diary_entry_bottom_fade)
        bottomMenu = findViewById(R.id.diary_bottom_menu)
        selectedDateLabel = findViewById(R.id.tv_diary_selected_date)
        diaryEditor = findViewById(R.id.et_diary_inline_text)
        diaryEditorLayout = findViewById(R.id.til_diary_inline_text)
        diaryEditorKeyListener = diaryEditor.keyListener
            ?: android.text.method.TextKeyListener.getInstance()
        emptyDiary = findViewById(R.id.tv_diary_empty)
        photoCount = findViewById(R.id.tv_diary_photo_count)
        editDiaryLink = findViewById(R.id.btn_diary_edit)
        saveDiaryButton = findViewById(R.id.btn_diary_save)
        cancelDiaryButton = findViewById(R.id.btn_diary_cancel_edit)
        addPhotoLink = findViewById(R.id.btn_diary_add_photo)
        photoHint = findViewById(R.id.tv_diary_photo_hint)
        photoCanvas = findViewById(R.id.diary_photo_canvas)
        photoLayer = DiaryPhotoLayer(photoCanvas) { handle -> confirmDeletePhoto(handle) }

        val today = todayDate()
        selectedDate = savedInstanceState?.getString(KEY_SELECTED_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: today
        isEditingEntry = savedInstanceState?.getBoolean(KEY_IS_EDITING) ?: false
        editStartedFromEmptyHome =
            savedInstanceState?.getBoolean(KEY_EDIT_STARTED_FROM_EMPTY_HOME) ?: false
        val startupDraft = if (savedInstanceState == null) readEditDraft(dateKey(today)) else null
        if (startupDraft != null) {
            selectedDate = today
            isEditingEntry = true
            editStartedFromEmptyHome = startupDraft.startedFromEmptyHome
        }
        hasSelectedDate = savedInstanceState?.getBoolean(KEY_HAS_SELECTED_DATE)
            ?: (!DiaryStore.load(this, dateKey(today)).isEmpty() || startupDraft != null)
        isEditingEntry = hasSelectedDate && isEditingEntry
        diaryEntryScroll.visibility = if (hasSelectedDate) View.VISIBLE else View.GONE
        diaryEntryScroll.setOnScrollChangeListener { _, _, scrollY, _, _ -> updateEntryFades(scrollY) }
        diaryEntryScroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateEntryFades(diaryEntryScroll.scrollY)
        }

        enableEntryPanelScrolling()
        diaryEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (isBindingDiaryText || !isEditingEntry) return
                val entry = currentEntry ?: return
                val updatedEntry = entry.copy(text = text?.toString().orEmpty())
                currentEntry = updatedEntry
                updateLiveContribution(updatedEntry)
                diaryEntryScroll.post { updateEntryFades(diaryEntryScroll.scrollY) }
            }

            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })

        findViewById<TextView>(R.id.btn_diary_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_diary_palette).setOnClickListener { showPaletteSettings() }
        findViewById<TextView>(R.id.btn_diary_backup).setOnClickListener { showBackupDialog() }
        val openGraph = View.OnClickListener { showContributionDialog() }
        findViewById<View>(R.id.diary_mini_graph_panel).setOnClickListener(openGraph)
        miniGraph.setOnClickListener(openGraph)
        editDiaryLink.setOnClickListener { beginInlineEditing() }
        saveDiaryButton.setOnClickListener { saveInlineEditing() }
        cancelDiaryButton.setOnClickListener { cancelInlineEditing() }
        addPhotoLink.setOnClickListener { pickDiaryImage.launch("image/*") }

        setupMiniWeekdayLabels()
        palette = DiaryPaletteStore.load(this)
        applyPalette()
        refreshDiary()
    }

    override fun onResume() {
        super.onResume()
        refreshDiary()
        lifecycleScope.launch {
            DiaryBackupSync.backupIfDirty(this@DiaryCalendarActivity)
        }
    }

    override fun onPause() {
        if (isEditingEntry) writeEditDraft()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (isEditingEntry) writeEditDraft()
        outState.putString(KEY_SELECTED_DATE, dateKey(selectedDate))
        outState.putBoolean(KEY_HAS_SELECTED_DATE, hasSelectedDate)
        outState.putBoolean(KEY_IS_EDITING, isEditingEntry)
        outState.putBoolean(KEY_EDIT_STARTED_FROM_EMPTY_HOME, editStartedFromEmptyHome)
        super.onSaveInstanceState(outState)
    }

    private fun setupMiniWeekdayLabels() {
        val labels = findViewById<LinearLayout>(R.id.diary_mini_weekday_labels)
        labels.removeAllViews()
        DiaryContributionPolicy.weekdayLabels.forEach { day ->
            labels.addView(TextView(this).apply {
                text = day
                gravity = Gravity.CENTER
                textSize = 8f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            })
        }
    }

    private fun enableEntryPanelScrolling() {
        var lastTouchY = 0f
        diaryEditor.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.y
                    view.parent?.requestDisallowInterceptTouchEvent(
                        view.canScrollVertically(-1) || view.canScrollVertically(1)
                    )
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.y - lastTouchY
                    if (deltaY != 0f) {
                        val direction = if (deltaY > 0f) -1 else 1
                        view.parent?.requestDisallowInterceptTouchEvent(view.canScrollVertically(direction))
                    }
                    lastTouchY = event.y
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun refreshDiary() {
        entryLengths = DiaryStore.textLengths(this)
        updateDiaryActionButtons()
        if (hasSelectedDate) {
            val key = dateKey(selectedDate)
            if (currentEntry?.date != key || !isEditingEntry) {
                loadSelectedEntry(key)
            }
            renderSelectedEntry()
            if (isEditingEntry) currentEntry?.let { mergeLiveEntryLength(it) }
        } else {
            currentEntry = null
            savedEntry = null
            diaryEntryScroll.visibility = View.GONE
            photoLayer.setInteractive(false)
            entryFadeOverlay.alpha = 0f
            entryBottomFadeOverlay.alpha = 0f
        }
        renderMiniGraph()
        updateEntryFades(diaryEntryScroll.scrollY)
    }

    private fun loadSelectedEntry(key: String) {
        val draft = if (isEditingEntry) readEditDraft(key) else null
        val entry = draft?.working ?: DiaryStore.load(this, key)
        currentEntry = entry
        savedEntry = draft?.original ?: entry
        isBindingDiaryText = true
        diaryEditor.setText(entry.text)
        isBindingDiaryText = false
        photoLayerReady = false
        photoLayer.restore(emptyList())
    }

    private fun updateDiaryActionButtons() {
        editDiaryLink.visibility = if (isEditingEntry) View.GONE else View.VISIBLE
        saveDiaryButton.visibility = if (isEditingEntry) View.VISIBLE else View.GONE
        cancelDiaryButton.visibility = if (isEditingEntry) View.VISIBLE else View.GONE
    }

    private fun renderSelectedEntry() {
        val entry = currentEntry ?: return
        val hasText = entry.text.isNotBlank()
        val hasPhotos = entry.photos.isNotEmpty()
        selectedDateLabel.text = displayDate(selectedDate)
        diaryEntryScroll.visibility = View.VISIBLE
        updateDiaryActionButtons()
        diaryInputLayoutHint(isEditingEntry)
        diaryEditorLayout.visibility = if (isEditingEntry || hasText) View.VISIBLE else View.GONE
        setInlineEditorEditable(isEditingEntry)

        if (isEditingEntry) {
            emptyDiary.visibility = View.GONE
            photoCount.visibility = View.GONE
            addPhotoLink.visibility = View.VISIBLE
            photoCanvas.visibility = if (hasPhotos) View.VISIBLE else View.GONE
            photoHint.visibility = if (hasPhotos) View.VISIBLE else View.GONE
            photoLayer.setInteractive(true)
            if (!photoLayerReady) restorePhotoLayer(entry.photos)
        } else {
            emptyDiary.text = if (hasPhotos) {
                "本文はありません。写真が${entry.photos.size}枚あります。"
            } else {
                "この日の日記はまだありません。"
            }
            emptyDiary.visibility = if (hasText) View.GONE else View.VISIBLE
            photoCount.text = if (hasPhotos) "写真：${entry.photos.size}枚" else ""
            photoCount.visibility = if (hasPhotos) View.VISIBLE else View.GONE
            addPhotoLink.visibility = View.GONE
            photoHint.visibility = View.GONE
            photoCanvas.visibility = if (hasPhotos) View.VISIBLE else View.GONE
            photoLayer.setInteractive(false)
            if (!photoLayerReady) restorePhotoLayer(entry.photos)
        }
        diaryEntryScroll.post { updateEntryFades(diaryEntryScroll.scrollY) }
    }

    private fun diaryInputLayoutHint(editing: Boolean) {
        diaryEditorLayout.hint = if (editing) "今日のできごと" else null
    }

    private fun setInlineEditorEditable(editable: Boolean) {
        if (editable) {
            diaryEditor.setTextIsSelectable(false)
            diaryEditor.keyListener = diaryEditorKeyListener
            diaryEditor.isFocusable = true
            diaryEditor.isFocusableInTouchMode = true
            diaryEditor.isCursorVisible = true
            diaryEditor.isLongClickable = true
        } else {
            diaryEditor.clearFocus()
            diaryEditor.keyListener = null
            diaryEditor.setTextIsSelectable(true)
            diaryEditor.isCursorVisible = false
            diaryEditor.isLongClickable = true
        }
    }

    private fun restorePhotoLayer(photos: List<DiaryPhoto>) {
        photoLayerReady = photos.isEmpty()
        photoLayer.restore(photos) {
            photoLayerReady = true
            diaryEntryScroll.post { updateEntryFades(diaryEntryScroll.scrollY) }
        }
    }

    private fun renderMiniGraph() {
        val today = todayDate()
        miniGraph.show(
            DiaryContributionPolicy.recentWeeks(today),
            entryLengths,
            palette,
            today
        )
    }

    private fun mergeLiveEntryLength(entry: DiaryEntry) {
        if (entry.date != dateKey(selectedDate)) return
        val updated = entryLengths.toMutableMap()
        if (entry.text.isNotBlank() || entry.photos.isNotEmpty()) {
            updated[entry.date] = entry.text.length
        } else {
            updated.remove(entry.date)
        }
        entryLengths = updated
    }

    private fun updateLiveContribution(entry: DiaryEntry) {
        mergeLiveEntryLength(entry)
        renderMiniGraph()
    }

    private fun selectDate(date: LocalDate) {
        if (isEditingEntry) {
            if (date == selectedDate) return
            MaterialAlertDialogBuilder(this)
                .setTitle("日記を編集中です")
                .setMessage("編集内容を保存するか破棄してから、別の日付を表示できます。")
                .setPositiveButton("保存して移動") { _, _ ->
                    saveInlineEditing()
                    showSelectedDate(date)
                }
                .setNeutralButton("破棄して移動") { _, _ ->
                    cancelInlineEditing()
                    showSelectedDate(date)
                }
                .setNegativeButton("編集を続ける", null)
                .show()
            return
        }
        showSelectedDate(date)
    }

    private fun showSelectedDate(date: LocalDate) {
        selectedDate = date
        hasSelectedDate = true
        isEditingEntry = false
        editStartedFromEmptyHome = false
        currentEntry = null
        savedEntry = null
        refreshDiary()
        diaryEntryScroll.post {
            diaryEntryScroll.scrollTo(0, 0)
            updateEntryFades(0)
        }
    }

    private fun beginInlineEditing() {
        if (!hasSelectedDate) {
            selectedDate = todayDate()
            hasSelectedDate = true
            editStartedFromEmptyHome = true
            currentEntry = null
        }

        isEditingEntry = true
        val key = dateKey(selectedDate)
        val draft = readEditDraft(key)
        if (draft != null) {
            currentEntry = draft.working
            savedEntry = draft.original
            editStartedFromEmptyHome = draft.startedFromEmptyHome
            isBindingDiaryText = true
            diaryEditor.setText(draft.working.text)
            isBindingDiaryText = false
            photoLayerReady = false
            photoLayer.restore(emptyList())
        } else if (currentEntry?.date != key) {
            loadSelectedEntry(key)
        }
        if (savedEntry == null) savedEntry = currentEntry

        renderSelectedEntry()
        currentEntry?.let { mergeLiveEntryLength(it) }
        renderMiniGraph()
        diaryEntryScroll.post { updateEntryFades(diaryEntryScroll.scrollY) }
        diaryEditor.requestFocus()
        diaryEditor.setSelection(diaryEditor.text?.length ?: 0)
        diaryEditor.post {
            val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(diaryEditor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun saveInlineEditing() {
        if (!isEditingEntry) return
        val updated = currentEntrySnapshot() ?: return
        val changed = updated != savedEntry
        DiaryStore.save(this, updated)
        if (changed) {
            DiaryBackupSync.markDirty(this)
            DiaryBackupSync.backupInBackground(this)
        }
        currentEntry = updated
        savedEntry = updated
        clearEditDraft(updated.date)
        isEditingEntry = false
        hideDiaryKeyboard()

        val shouldReturnToEmptyHome = editStartedFromEmptyHome && updated.isEmpty()
        editStartedFromEmptyHome = false
        if (shouldReturnToEmptyHome) {
            hasSelectedDate = false
            currentEntry = null
            savedEntry = null
            diaryEntryScroll.visibility = View.GONE
        } else {
            hasSelectedDate = true
        }
        DiaryStore.purgeUnusedImages(this)
        entryLengths = DiaryStore.textLengths(this)
        if (hasSelectedDate) currentEntry?.let { mergeLiveEntryLength(it) }
        updateDiaryActionButtons()
        if (hasSelectedDate) renderSelectedEntry()
        renderMiniGraph()
        updateEntryFades(diaryEntryScroll.scrollY)
    }

    private fun cancelInlineEditing() {
        if (!isEditingEntry) return
        val original = savedEntry ?: DiaryStore.load(this, dateKey(selectedDate))
        clearEditDraft(original.date)
        currentEntry = original
        savedEntry = original
        isBindingDiaryText = true
        diaryEditor.setText(original.text)
        isBindingDiaryText = false
        photoLayerReady = false
        photoLayer.setInteractive(false)
        photoLayer.restore(emptyList())
        isEditingEntry = false
        hideDiaryKeyboard()

        val shouldReturnToEmptyHome = editStartedFromEmptyHome && original.isEmpty()
        editStartedFromEmptyHome = false
        if (shouldReturnToEmptyHome) {
            hasSelectedDate = false
            currentEntry = null
            savedEntry = null
            diaryEntryScroll.visibility = View.GONE
        } else {
            hasSelectedDate = true
        }
        DiaryStore.purgeUnusedImages(this)
        entryLengths = DiaryStore.textLengths(this)
        if (hasSelectedDate) currentEntry?.let { mergeLiveEntryLength(it) }
        updateDiaryActionButtons()
        if (hasSelectedDate) renderSelectedEntry()
        renderMiniGraph()
        updateEntryFades(diaryEntryScroll.scrollY)
    }

    private fun hideDiaryKeyboard() {
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        keyboard.hideSoftInputFromWindow(diaryEditor.windowToken, 0)
        diaryEditor.clearFocus()
    }

    private fun currentEntrySnapshot(): DiaryEntry? {
        val entry = currentEntry ?: return null
        val latestText = if (isEditingEntry) diaryEditor.text?.toString().orEmpty() else entry.text
        val latestPhotos = if (isEditingEntry && photoLayerReady) photoLayer.snapshot() else entry.photos
        return entry.copy(text = latestText, photos = latestPhotos)
    }

    private fun editDraftFile(date: String): File = File(cacheDir, "diary-edit-$date.json")

    private fun writeEditDraft() {
        if (!isEditingEntry) return
        val original = savedEntry ?: return
        val working = currentEntrySnapshot() ?: return
        val encoded = JSONObject()
            .put("date", working.date)
            .put("startedFromEmptyHome", editStartedFromEmptyHome)
            .put("original", DiaryJson.encode(original))
            .put("working", DiaryJson.encode(working))
            .toString()
        runCatching { AtomicFiles.writeUtf8(editDraftFile(working.date), encoded) }
    }

    private fun readEditDraft(date: String): DiaryEditDraft? {
        val raw = AtomicFiles.readUtf8(editDraftFile(date)) ?: return null
        val draft = runCatching {
            val json = JSONObject(raw)
            if (json.optString("date") != date || !json.has("original") || !json.has("working")) {
                null
            } else {
                DiaryEditDraft(
                    original = DiaryJson.decode(date, json.optString("original")),
                    working = DiaryJson.decode(date, json.optString("working")),
                    startedFromEmptyHome = json.optBoolean("startedFromEmptyHome", false)
                )
            }
        }.getOrNull()
        if (draft == null) clearEditDraft(date)
        return draft
    }

    private fun clearEditDraft(date: String) {
        editDraftFile(date).delete()
        File(cacheDir, "diary-edit-$date.json.tmp").delete()
    }

    private fun handleDiaryImageSelection(uri: android.net.Uri?) {
        if (uri == null || !isEditingEntry) return
        val fileName = runCatching {
            contentResolver.openInputStream(uri)?.use { DiaryStore.importImage(this, it) }
        }.getOrNull()
        if (fileName == null) {
            Toast.makeText(this, "画像を読み込めませんでした。", Toast.LENGTH_SHORT).show()
            return
        }

        val entry = currentEntry ?: DiaryStore.load(this, dateKey(selectedDate))
        val photos = if (photoLayerReady) photoLayer.snapshot() else entry.photos
        val newPhoto = DiaryPhoto(
            fileName = fileName,
            xRatio = (1f - DiaryLayoutPolicy.DEFAULT_WIDTH_RATIO) / 2f,
            yRatio = 0.05f,
            widthRatio = DiaryLayoutPolicy.DEFAULT_WIDTH_RATIO
        )
        val updatedEntry = entry.copy(photos = photos + newPhoto)
        currentEntry = updatedEntry
        photoCanvas.visibility = View.VISIBLE
        photoHint.visibility = View.VISIBLE
        photoLayer.setInteractive(true)
        restorePhotoLayer(updatedEntry.photos)
        updateLiveContribution(updatedEntry)
    }

    private fun confirmDeletePhoto(handle: DiaryPhotoHandle) {
        MaterialAlertDialogBuilder(this)
            .setTitle("この画像を削除しますか？")
            .setMessage("この日記から画像を取り除きます。")
            .setPositiveButton("削除") { _, _ ->
                photoLayer.remove(handle)
                val entry = currentEntry ?: return@setPositiveButton
                val updatedEntry = entry.copy(photos = photoLayer.snapshot())
                currentEntry = updatedEntry
                if (updatedEntry.photos.isEmpty()) {
                    photoCanvas.visibility = View.GONE
                    photoHint.visibility = View.GONE
                }
                updateLiveContribution(updatedEntry)
                diaryEntryScroll.post { updateEntryFades(diaryEntryScroll.scrollY) }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateEntryFadeBackground() {
        val baseAlpha = Color.alpha(palette.background)
        val coverAlphas = intArrayOf(255, 248, 215, 128, 40, 7, 0)
        val positions = floatArrayOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f, 1f)
        val red = Color.red(palette.background)
        val green = Color.green(palette.background)
        val blue = Color.blue(palette.background)
        val colors = coverAlphas.map { alpha ->
            Color.argb(alpha * baseAlpha / 255, red, green, blue)
        }.toIntArray()
        entryFadeOverlay.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            colors
        ).apply { setColors(colors, positions) }
        entryBottomFadeOverlay.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            colors
        ).apply { setColors(colors, positions) }
        updateEntryFades(diaryEntryScroll.scrollY)
    }

    private fun updateEntryFades(scrollY: Int) {
        if (!hasSelectedDate || diaryEntryScroll.visibility != View.VISIBLE) {
            entryFadeOverlay.alpha = 0f
            entryBottomFadeOverlay.alpha = 0f
            return
        }
        val topProgress = (scrollY.coerceAtLeast(0) / dp(24).toFloat()).coerceIn(0f, 1f)
        entryFadeOverlay.alpha = easeInOut(topProgress)

        val contentHeight = diaryEntryScroll.getChildAt(0)?.height ?: 0
        val viewportHeight = (diaryEntryScroll.height - diaryEntryScroll.paddingTop - diaryEntryScroll.paddingBottom)
            .coerceAtLeast(0)
        val scrollRange = (contentHeight - viewportHeight).coerceAtLeast(0)
        val distanceToBottom = (scrollRange - scrollY.coerceIn(0, scrollRange)).coerceAtLeast(0)
        val bottomProgress = if (scrollRange == 0) 0f else {
            (distanceToBottom / dp(44).toFloat()).coerceIn(0f, 1f)
        }
        entryBottomFadeOverlay.alpha = easeInOut(bottomProgress)
    }

    private fun easeInOut(progress: Float): Float {
        val value = progress.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
    }

    private fun displayDate(date: LocalDate): String {
        val weekday = DiaryContributionPolicy.weekdayLabels[date.dayOfWeek.value % 7]
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日（${weekday}）"
    }

    private fun dateKey(date: LocalDate): String =
        DiaryStore.dateKey(date.year, date.monthValue, date.dayOfMonth)

    private fun todayDate(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun showContributionDialog() {
        val dialog = Dialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_diary_contribution, null, false)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)

        val dialogRoot = content.findViewById<View>(R.id.diary_graph_dialog_root)
        val yearLabel = content.findViewById<TextView>(R.id.tv_diary_graph_year)
        val previousYear = content.findViewById<TextView>(R.id.btn_diary_graph_previous_year)
        val nextYear = content.findViewById<TextView>(R.id.btn_diary_graph_next_year)
        val close = content.findViewById<TextView>(R.id.btn_diary_graph_close)
        val weekdayLabels = content.findViewById<LinearLayout>(R.id.diary_graph_weekday_labels)
        val monthLabels = content.findViewById<LinearLayout>(R.id.diary_graph_month_labels)
        val weekColumns = content.findViewById<LinearLayout>(R.id.diary_graph_week_columns)
        val horizontalScroll = content.findViewById<HorizontalScrollView>(R.id.diary_graph_horizontal_scroll)
        val today = todayDate()
        val currentYear = today.year
        val minimumYear = minimumDiaryYear()
        var shownYear = currentYear.coerceIn(minimumYear, currentYear)

        close.setOnClickListener { dialog.dismiss() }
        previousYear.setOnClickListener {
            if (shownYear > minimumYear) {
                shownYear -= 1
                renderDetailYear(
                    shownYear, yearLabel, previousYear, nextYear,
                    weekdayLabels, monthLabels, weekColumns, horizontalScroll, dialog
                )
            }
        }
        nextYear.setOnClickListener {
            if (shownYear < currentYear) {
                shownYear += 1
                renderDetailYear(
                    shownYear, yearLabel, previousYear, nextYear,
                    weekdayLabels, monthLabels, weekColumns, horizontalScroll, dialog
                )
            }
        }
        styleDialogText(dialogRoot)
        renderDetailYear(
            shownYear, yearLabel, previousYear, nextYear,
            weekdayLabels, monthLabels, weekColumns, horizontalScroll, dialog
        )

        dialog.show()
        val dialogWidth = (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(280))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(palette.background))
            setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
        }
    }

    private fun renderDetailYear(
        year: Int,
        yearLabel: TextView,
        previousYear: TextView,
        nextYear: TextView,
        weekdayLabels: LinearLayout,
        monthLabels: LinearLayout,
        weekColumns: LinearLayout,
        horizontalScroll: HorizontalScrollView,
        dialog: Dialog
    ) {
        val today = todayDate()
        val weeks = DiaryContributionPolicy.yearWeeks(year)
        val cellWidth = dp(GRAPH_CELL_WIDTH_DP)
        val cellHeight = dp(GRAPH_CELL_HEIGHT_DP)
        val monthHeight = dp(GRAPH_MONTH_HEIGHT_DP)

        yearLabel.text = "${year}年"
        previousYear.isEnabled = year > minimumDiaryYear()
        previousYear.alpha = if (previousYear.isEnabled) 1f else 0.38f
        nextYear.isEnabled = year < today.year
        nextYear.alpha = if (nextYear.isEnabled) 1f else 0.38f

        weekdayLabels.removeAllViews()
        weekdayLabels.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), monthHeight)
        })
        DiaryContributionPolicy.weekdayLabels.forEach { label ->
            weekdayLabels.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(palette.text)
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(GRAPH_ROW_HEIGHT_DP))
            })
        }

        monthLabels.removeAllViews()
        weekColumns.removeAllViews()
        weeks.forEach { week ->
            val monthStart = week.firstOrNull { it?.dayOfMonth == 1 }
            monthLabels.addView(TextView(this).apply {
                text = monthStart?.let { "${it.monthValue}月" }.orEmpty()
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(palette.text)
                layoutParams = LinearLayout.LayoutParams(cellWidth, monthHeight)
            })

            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            week.forEach { date ->
                val cell = TextView(this).apply {
                    gravity = Gravity.CENTER
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    layoutParams = LinearLayout.LayoutParams(dp(GRAPH_CELL_WIDTH_DP - 4), cellHeight).apply {
                        rightMargin = dp(4)
                        bottomMargin = dp(2)
                    }
                }
                if (date == null) {
                    cell.text = ""
                    cell.isEnabled = false
                    cell.background = cellBackground(0, isToday = false)
                } else {
                    val key = dateKey(date)
                    val count = entryLengths[key] ?: 0
                    val hasEntry = entryLengths.containsKey(key)
                    val level = DiaryContributionPolicy.level(count, hasEntry)
                    cell.text = date.dayOfMonth.toString()
                    cell.contentDescription = if (hasEntry) {
                        "${displayDate(date)}、日記あり、${count}文字"
                    } else {
                        "${displayDate(date)}、日記なし"
                    }
                    cell.setTooltipText(displayDate(date))
                    cell.setTextColor(if (level >= 3) palette.background else palette.text)
                    cell.background = cellBackground(level, date == today)
                    cell.isClickable = true
                    cell.isFocusable = true
                    cell.setOnClickListener {
                        dialog.dismiss()
                        selectDate(date)
                    }
                }
                column.addView(cell)
            }
            weekColumns.addView(column, LinearLayout.LayoutParams(cellWidth, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        horizontalScroll.post {
            if (year == today.year) {
                val todayWeek = weeks.indexOfFirst { today in it.filterNotNull() }
                if (todayWeek >= 0) {
                    horizontalScroll.scrollTo(
                        (todayWeek * cellWidth - horizontalScroll.width / 2).coerceAtLeast(0),
                        0
                    )
                }
            } else {
                horizontalScroll.fullScroll(View.FOCUS_RIGHT)
            }
        }
    }


    private fun minimumDiaryYear(): Int {
        val firstSavedYear = DiaryStore.savedDates(this)
            .mapNotNull { it.take(4).toIntOrNull() }
            .minOrNull()
        return minOf(MIN_GRAPH_YEAR, firstSavedYear ?: MIN_GRAPH_YEAR).coerceAtLeast(1)
    }

    private fun cellBackground(level: Int, isToday: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.cellColor(level))
            setStroke(dp(if (isToday) 2 else 1), if (isToday) palette.text else palette.border)
        }

    private fun styleDialogText(root: View) {
        root.setBackgroundColor(palette.background)
        root.background = panelBackground()
        tintTextViews(root, palette.text)
    }

    private fun showPaletteSettings() {
        MaterialAlertDialogBuilder(this)
            .setTitle("配色設定")
            .setItems(arrayOf("背景色を選ぶ", "文字色を選ぶ", "初期配色に戻す")) { _, which ->
                when (which) {
                    0 -> choosePaletteColor("背景色", palette.background, isBackground = true) { color ->
                        DiaryPaletteStore.saveBackground(this, color)
                        applyPalette()
                        refreshDiary()
                    }
                    1 -> choosePaletteColor("文字色", palette.text, isBackground = false) { color ->
                        DiaryPaletteStore.saveText(this, color)
                        applyPalette()
                        refreshDiary()
                    }
                    2 -> {
                        DiaryPaletteStore.reset(this)
                        palette = DiaryPaletteStore.load(this)
                        applyPalette()
                        refreshDiary()
                    }
                }
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun choosePaletteColor(
        title: String,
        currentColor: Int,
        isBackground: Boolean,
        onChosen: (Int) -> Unit
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_diary_palette, null, false)
        val preview = content.findViewById<TextView>(R.id.diary_palette_preview)
        val hexLabel = content.findViewById<TextView>(R.id.diary_palette_hex)
        val colorWheel = content.findViewById<DiaryColorWheelView>(R.id.diary_palette_wheel)
        var selectedColor = currentColor

        fun updatePreview(color: Int) {
            selectedColor = color
            val sampleBackground = if (isBackground) color else palette.background
            val sampleText = if (isBackground) palette.text else color
            preview.text = if (isBackground) "文字色の見本" else "背景色の見本"
            preview.setTextColor(sampleText)
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5).toFloat()
                setColor(sampleBackground)
                val border = if (Color.luminance(sampleBackground) < 0.45f) Color.WHITE else Color.DKGRAY
                setStroke(dp(1), border)
            }
            hexLabel.text = String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
        }

        colorWheel.setColor(currentColor)
        colorWheel.setOnColorChangedListener(::updatePreview)
        updatePreview(currentColor)

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(content)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("この色にする") { _, _ -> onChosen(selectedColor) }
            .show()
    }

    private fun applyPalette() {
        palette = DiaryPaletteStore.load(this)
        pageRoot.setBackgroundColor(palette.background)
        tintTextViews(pageRoot, palette.text)
        diaryEntryScroll.setBackgroundColor(palette.background)
        bottomMenu.setBackgroundColor(palette.background)
        diaryEditor.setTextColor(palette.text)
        diaryEditor.setHintTextColor(palette.mutedText)
        diaryEditorLayout.setHintTextColor(ColorStateList.valueOf(palette.text))
        emptyDiary.setTextColor(palette.mutedText)
        photoCount.setTextColor(palette.mutedText)
        photoHint.setTextColor(palette.mutedText)
        photoCanvas.setBackgroundColor(Color.TRANSPARENT)
        updateEntryFadeBackground()

        findViewById<View>(R.id.diary_mini_graph_panel).setBackgroundColor(palette.background)
        val today = todayDate()
        miniGraph.show(
            DiaryContributionPolicy.recentWeeks(today),
            entryLengths,
            palette,
            today
        )

        listOf(
            R.id.btn_diary_back,
            R.id.btn_diary_palette,
            R.id.btn_diary_backup,
            R.id.btn_diary_edit,
            R.id.btn_diary_save,
            R.id.btn_diary_cancel_edit,
            R.id.btn_diary_add_photo
        ).forEach { findViewById<TextView>(it).apply {
            text = linkText(text.toString())
            setTextColor(palette.text)
            isClickable = true
            isFocusable = true
        } }

        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(palette.background)
        setStroke(dp(1), palette.border)
    }

    private fun tintTextViews(view: View, color: Int) {
        if (view is TextView) {
            view.setTextColor(color)
            view.setLinkTextColor(color)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) tintTextViews(view.getChildAt(index), color)
        }
    }

    private fun linkText(value: CharSequence): CharSequence {
        val plain = value.toString()
        return SpannableString(plain).apply {
            if (plain.isNotEmpty()) setSpan(UnderlineSpan(), 0, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun backupStatusText(): String {
        val name = DiaryBackupSync.selectedName(this)
            ?: return "バックアップ先：未設定（［バックアップ］から選択）"
        val last = DiaryBackupSync.lastBackupMs(this)
        val error = DiaryBackupSync.lastError(this)
        val hasCounts = DiaryBackupSync.hasBackupCounts(this)
        val status = when {
            error != null -> "バックアップ先：$name（更新に失敗）"
            !hasCounts -> "バックアップ先：$name（既存バックアップの安全確認が必要）"
            last == 0L -> "バックアップ先：$name（未更新）"
            else -> "バックアップ先：$name（最終更新 ${DiaryStore.displayDate(dateOf(last))}）"
        }
        return if (error == null) status else "$status\n$error"
    }

    private fun dateOf(epochMs: Long): String {
        val date = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return DiaryStore.dateKey(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun showBackupDialog() {
        val (_, view) = Md3PopupDialog.inflate(this, R.layout.dialog_diary_backup)
        val hasBackup = DiaryBackupSync.selectedUri(this) != null
        view.findViewById<MaterialTextView>(R.id.tv_backup_dialog_status).text = backupStatusText()
        view.findViewById<MaterialButton>(R.id.btn_backup_choose).text =
            if (hasBackup) "保存先ファイルを変更" else "保存先ファイルを選択"
        view.findViewById<MaterialButton>(R.id.btn_backup_restore).visibility =
            if (hasBackup) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btn_backup_now).visibility =
            if (hasBackup) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btn_backup_disconnect).visibility =
            if (hasBackup) View.VISIBLE else View.GONE

        val dialog = Md3PopupDialog.show(this, view)
        view.findViewById<MaterialButton>(R.id.btn_backup_choose).setOnClickListener {
            dialog.dismiss()
            showBackupFileOptions()
        }
        view.findViewById<MaterialButton>(R.id.btn_backup_restore).setOnClickListener {
            dialog.dismiss()
            confirmRestoreBackup()
        }
        view.findViewById<MaterialButton>(R.id.btn_backup_now).setOnClickListener {
            dialog.dismiss()
            runManualBackup()
        }
        view.findViewById<MaterialButton>(R.id.btn_backup_disconnect).setOnClickListener {
            dialog.dismiss()
            confirmDisconnectBackup()
        }
        view.findViewById<MaterialButton>(R.id.btn_backup_close).setOnClickListener { dialog.dismiss() }
    }

    private fun showBackupFileOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle("日記バックアップファイル")
            .setItems(arrayOf("新しいバックアップを作成", "既存の日記バックアップを選択")) { _, which ->
                if (which == 0) launchCreateBackupPicker() else launchOpenBackupPicker()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun launchCreateBackupPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = DiaryBackupArchive.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, DiaryBackupArchive.SUGGESTED_FILE_NAME)
            addFlags(safGrantFlags())
        }
        try {
            createBackupFile.launch(intent)
        } catch (error: Exception) {
            showBackupOutcome(DiaryBackupSync.Outcome(false, error.localizedMessage ?: "ファイル選択画面を開けませんでした。"))
        }
    }

    private fun launchOpenBackupPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addFlags(safGrantFlags())
        }
        try {
            openBackupFile.launch(intent)
        } catch (error: Exception) {
            showBackupOutcome(DiaryBackupSync.Outcome(false, error.localizedMessage ?: "ファイル選択画面を開けませんでした。"))
        }
    }

    private fun safGrantFlags(): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    private fun handleBackupSelection(result: ActivityResult, isNewFile: Boolean) {
        if (result.resultCode != Activity.RESULT_OK) return
        val data = result.data
        val uri = data?.data
        if (data == null || uri == null) {
            showBackupOutcome(DiaryBackupSync.Outcome(false, "選択したファイルの場所を取得できませんでした。"))
            return
        }
        val grantFlags = data.flags
        if (isEditingEntry) {
            MaterialAlertDialogBuilder(this)
                .setTitle("編集中の日記があります")
                .setMessage("バックアップ先の設定前に、編集内容を保存して接続しますか？")
                .setNegativeButton("キャンセル", null)
                .setPositiveButton("保存して接続") { _, _ ->
                    saveInlineEditing()
                    connectBackupFile(uri, grantFlags, isNewFile)
                }
                .show()
        } else {
            connectBackupFile(uri, grantFlags, isNewFile)
        }
    }

    private fun connectBackupFile(uri: android.net.Uri, grantFlags: Int, isNewFile: Boolean) {
        lifecycleScope.launch {
            val outcome = DiaryBackupSync.connect(this@DiaryCalendarActivity, uri, grantFlags, isNewFile)
            refreshDiary()
            showBackupOutcome(outcome)
        }
    }

    private fun runManualBackup(allowDestructiveOverwrite: Boolean = false) {
        if (isEditingEntry) {
            MaterialAlertDialogBuilder(this)
                .setTitle("編集中の日記があります")
                .setMessage("編集内容をバックアップに含めるには先に保存してください。保存済みの内容だけで更新することもできます。")
                .setNegativeButton("戻る", null)
                .setNeutralButton("保存せず更新") { _, _ -> performManualBackup(allowDestructiveOverwrite) }
                .setPositiveButton("保存して更新") { _, _ ->
                    saveInlineEditing()
                    performManualBackup(allowDestructiveOverwrite)
                }
                .show()
            return
        }
        performManualBackup(allowDestructiveOverwrite)
    }

    private fun performManualBackup(allowDestructiveOverwrite: Boolean) {
        lifecycleScope.launch {
            val outcome = DiaryBackupSync.backupNow(
                this@DiaryCalendarActivity,
                allowDestructiveOverwrite = allowDestructiveOverwrite
            )
            refreshDiary()
            showBackupOutcome(outcome)
        }
    }

    private fun showBackupOutcome(outcome: DiaryBackupSync.Outcome) {
        if (outcome.requiresOverwriteConfirmation) {
            MaterialAlertDialogBuilder(this)
                .setTitle("上書きを停止しました")
                .setMessage(outcome.message)
                .setNegativeButton("キャンセル", null)
                .setNeutralButton("既存バックアップから復元") { _, _ -> confirmRestoreBackup() }
                .setPositiveButton("この状態で上書き") { _, _ -> runManualBackup(allowDestructiveOverwrite = true) }
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (outcome.success) "バックアップ" else "バックアップを更新できませんでした")
            .setMessage(outcome.message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun confirmRestoreBackup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("選択済みバックアップから復元")
            .setMessage(
                "バックアップZIPから端末にない日記や写真を取り込みます。端末にあるデータは削除しません。" +
                    (if (isEditingEntry) "編集内容を先に保存し、復元後にバックアップを更新します。"
                    else "復元後にバックアップを更新します。")
            )
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("復元して更新") { _, _ ->
                if (isEditingEntry) saveInlineEditing()
                lifecycleScope.launch {
                    val outcome = DiaryBackupSync.restoreNow(this@DiaryCalendarActivity)
                    refreshDiary()
                    showBackupOutcome(outcome)
                }
            }
            .show()
    }

    private fun confirmDisconnectBackup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("保存先の設定を解除しますか？")
            .setMessage("設定とアクセス権だけを解除します。バックアップファイル自体は削除されません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("解除") { _, _ ->
                lifecycleScope.launch {
                    DiaryBackupSync.disconnect(this@DiaryCalendarActivity)
                    refreshDiary()
                }
            }
            .show()
    }

    companion object {
        private const val KEY_SELECTED_DATE = "selected_diary_date"
        private const val KEY_HAS_SELECTED_DATE = "has_selected_diary_date"
        private const val KEY_IS_EDITING = "is_editing_diary_entry"
        private const val KEY_EDIT_STARTED_FROM_EMPTY_HOME = "edit_started_from_empty_diary_home"
        private const val MIN_GRAPH_YEAR = 2000
        private const val GRAPH_CELL_WIDTH_DP = 40
        private const val GRAPH_CELL_HEIGHT_DP = 36
        private const val GRAPH_ROW_HEIGHT_DP = 38
        private const val GRAPH_MONTH_HEIGHT_DP = 28
    }
}
