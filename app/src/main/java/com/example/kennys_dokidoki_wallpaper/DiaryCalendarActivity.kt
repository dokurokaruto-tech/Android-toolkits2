package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 黒地に赤字の、古い個人サイト風の日記ページ。 */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var pageRoot: View
    private lateinit var miniGraph: DiaryContributionGraphView
    private lateinit var diaryEntryScroll: ScrollView
    private lateinit var selectedDateLabel: TextView
    private lateinit var diaryBody: TextView
    private lateinit var emptyDiary: TextView
    private lateinit var photoCount: TextView
    private lateinit var editDiaryLink: TextView

    private var selectedDate: LocalDate = LocalDate.now()
    private var hasSelectedDate = false
    private var palette: DiaryPalette = DiaryPalette(0xFF000000.toInt(), 0xFFFF2020.toInt())
    private var entryLengths: Map<String, Int> = emptyMap()

    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = true) }

    private val openBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        pageRoot = findViewById(R.id.diary_page_root)
        miniGraph = findViewById(R.id.diary_mini_graph)
        diaryEntryScroll = findViewById(R.id.diary_entry_scroll)
        selectedDateLabel = findViewById(R.id.tv_diary_selected_date)
        diaryBody = findViewById(R.id.tv_diary_body)
        emptyDiary = findViewById(R.id.tv_diary_empty)
        photoCount = findViewById(R.id.tv_diary_photo_count)
        editDiaryLink = findViewById(R.id.btn_diary_edit)
        selectedDate = savedInstanceState?.getString(KEY_SELECTED_DATE)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
        hasSelectedDate = savedInstanceState?.getBoolean(KEY_HAS_SELECTED_DATE) ?: false
        diaryEntryScroll.visibility = if (hasSelectedDate) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.btn_diary_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_diary_palette).setOnClickListener { showPaletteSettings() }
        findViewById<TextView>(R.id.btn_diary_backup).setOnClickListener { showBackupDialog() }
        val openGraph = View.OnClickListener { showContributionDialog() }
        findViewById<View>(R.id.diary_mini_graph_panel).setOnClickListener(openGraph)
        miniGraph.setOnClickListener(openGraph)
        editDiaryLink.setOnClickListener { openEditor(selectedDate) }

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_DATE, dateKey(selectedDate))
        outState.putBoolean(KEY_HAS_SELECTED_DATE, hasSelectedDate)
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

    private fun refreshDiary() {
        entryLengths = DiaryStore.textLengths(this)
        miniGraph.show(
            DiaryContributionPolicy.recentWeeks(LocalDate.now()),
            entryLengths,
            palette,
            selectedDate
        )
        if (hasSelectedDate) refreshSelectedEntry()
    }

    private fun refreshSelectedEntry() {
        val entry = DiaryStore.load(this, dateKey(selectedDate))
        val hasText = entry.text.isNotBlank()
        val hasPhotos = entry.photos.isNotEmpty()
        selectedDateLabel.text = displayDate(selectedDate)

        if (hasText) {
            diaryBody.text = entry.text
            diaryBody.visibility = View.VISIBLE
            emptyDiary.visibility = View.GONE
        } else {
            diaryBody.text = ""
            diaryBody.visibility = View.GONE
            emptyDiary.text = if (hasPhotos) {
                "本文はありません。写真が${entry.photos.size}枚あります。"
            } else {
                "この日の日記はまだありません。"
            }
            emptyDiary.visibility = View.VISIBLE
        }

        if (hasPhotos) {
            photoCount.text = "写真：${entry.photos.size}枚"
            photoCount.visibility = View.VISIBLE
        } else {
            photoCount.text = ""
            photoCount.visibility = View.GONE
        }
        editDiaryLink.text = linkText(
            if (hasText || hasPhotos) "[ この日の日記を編集する ]" else "[ この日に日記を書く ]"
        )
        diaryEntryScroll.visibility = View.VISIBLE
    }

    private fun selectDate(date: LocalDate) {
        selectedDate = date
        hasSelectedDate = true
        refreshDiary()
        diaryEntryScroll.post { diaryEntryScroll.scrollTo(0, 0) }
    }

    private fun openEditor(date: LocalDate) {
        startActivity(
            Intent(this, DiaryEditorActivity::class.java)
                .putExtra(DiaryEditorActivity.EXTRA_DATE, dateKey(date))
        )
    }

    private fun displayDate(date: LocalDate): String {
        val weekday = DiaryContributionPolicy.weekdayLabels[date.dayOfWeek.value % 7]
        return "${date.year}年${date.monthValue}月${date.dayOfMonth}日（${weekday}）"
    }

    private fun dateKey(date: LocalDate): String =
        DiaryStore.dateKey(date.year, date.monthValue, date.dayOfMonth)

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
        val currentYear = LocalDate.now().year
        val minimumYear = minimumDiaryYear()
        var shownYear = selectedDate.year.coerceIn(minimumYear, currentYear)

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
        val today = LocalDate.now()
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
                    cell.background = cellBackground(0, isSelected = false)
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
                    cell.background = cellBackground(level, date == selectedDate)
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
            if (year == selectedDate.year) {
                val selectedWeek = weeks.indexOfFirst { selectedDate in it.filterNotNull() }
                if (selectedWeek >= 0) {
                    horizontalScroll.scrollTo(
                        (selectedWeek * cellWidth - horizontalScroll.width / 2).coerceAtLeast(0),
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

    private fun cellBackground(level: Int, isSelected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(palette.cellColor(level))
            setStroke(dp(if (isSelected) 2 else 1), if (isSelected) palette.text else palette.border)
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
        emptyDiary.setTextColor(palette.mutedText)
        photoCount.setTextColor(palette.mutedText)

        findViewById<View>(R.id.diary_mini_graph_panel).setBackgroundColor(palette.background)
        miniGraph.show(
            DiaryContributionPolicy.recentWeeks(LocalDate.now()),
            entryLengths,
            palette,
            selectedDate
        )

        listOf(
            R.id.btn_diary_back,
            R.id.btn_diary_palette,
            R.id.btn_diary_backup,
            R.id.btn_diary_edit
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
        lifecycleScope.launch {
            val outcome = DiaryBackupSync.connect(this@DiaryCalendarActivity, uri, grantFlags, isNewFile)
            refreshDiary()
            showBackupOutcome(outcome)
        }
    }

    private fun runManualBackup(allowDestructiveOverwrite: Boolean = false) {
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
            .setMessage("バックアップZIPから端末にない日記や写真を取り込みます。端末にあるデータは削除しません。復元後にバックアップを更新します。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("復元して更新") { _, _ ->
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
        private const val MIN_GRAPH_YEAR = 2000
        private const val GRAPH_CELL_WIDTH_DP = 40
        private const val GRAPH_CELL_HEIGHT_DP = 36
        private const val GRAPH_ROW_HEIGHT_DP = 38
        private const val GRAPH_MONTH_HEIGHT_DP = 28
    }
}
