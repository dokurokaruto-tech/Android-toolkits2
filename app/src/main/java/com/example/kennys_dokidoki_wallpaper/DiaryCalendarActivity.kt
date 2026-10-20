package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 「私の図書館」の入口。日記・エッセイ・やりたい事リストを切り替える。 */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var datesAdapter: DiaryDateAdapter
    private lateinit var monthAdapter: DiaryMonthAdapter
    private lateinit var notesAdapter: LibraryNoteAdapter
    private lateinit var monthTitle: MaterialTextView
    private lateinit var backupStatus: MaterialTextView
    private lateinit var tabs: TabLayout
    private lateinit var calendarContent: View
    private lateinit var notesContent: View
    private lateinit var notesHeading: MaterialTextView
    private lateinit var notesEmpty: MaterialTextView
    private lateinit var notesList: RecyclerView
    private lateinit var addNoteButton: FloatingActionButton

    private var activeNoteKind = LibraryNoteKind.ESSAY
    private var shownYear = 0
    private var shownMonth = 0

    private val createBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = true) }

    private val openBackupFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result -> handleBackupSelection(result, isNewFile = false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_my_library)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onToolbarMenuItemClick)

        monthTitle = findViewById(R.id.tv_month_title)
        backupStatus = findViewById(R.id.tv_backup_status)
        calendarContent = findViewById(R.id.library_diary_content)
        notesContent = findViewById(R.id.library_notes_content)
        notesHeading = findViewById(R.id.tv_library_notes_heading)
        notesEmpty = findViewById(R.id.tv_library_notes_empty)
        notesList = findViewById(R.id.recycler_library_notes)
        addNoteButton = findViewById(R.id.fab_library_add_note)
        tabs = findViewById(R.id.tabs_my_library)

        findViewById<MaterialButton>(R.id.btn_diary_today).setOnClickListener {
            openDate(DiaryStore.todayKey())
        }
        findViewById<MaterialButton>(R.id.btn_month_prev).setOnClickListener { shiftMonth(-1) }
        findViewById<MaterialButton>(R.id.btn_month_next).setOnClickListener { shiftMonth(1) }

        setupWeekdayRow()
        setupMonthGrid()
        setupDateList()
        setupLibraryNotes()
        setupTabs()

        val today = LocalDate.now()
        shownYear = today.year
        shownMonth = today.monthValue
        val savedTab = getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE)
            .getInt(KEY_SELECTED_TAB, TAB_DIARY)
            .coerceIn(TAB_DIARY, TAB_WISH_LIST)
        tabs.getTabAt(savedTab)?.select()
        showSection(savedTab)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 前回のバックグラウンド更新が中断／失敗していた場合のみ再試行する。
        lifecycleScope.launch {
            LibraryBackupSync.backupIfDirty(this@DiaryCalendarActivity)
            refresh()
        }
    }

    private fun onToolbarMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_library_backup -> {
            showBackupDialog()
            true
        }
        else -> false
    }

    private fun setupTabs() {
        listOf("日記", "エッセイ", "やりたい事リスト").forEach { title ->
            tabs.addTab(tabs.newTab().setText(title), false)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showSection(position: Int) {
        val isDiary = position == TAB_DIARY
        calendarContent.visibility = if (isDiary) View.VISIBLE else View.GONE
        notesContent.visibility = if (isDiary) View.GONE else View.VISIBLE
        getSharedPreferences(LIBRARY_PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_SELECTED_TAB, position)
            .apply()

        if (!isDiary) {
            activeNoteKind = if (position == TAB_ESSAY) LibraryNoteKind.ESSAY else LibraryNoteKind.WISH_LIST
            notesHeading.text = activeNoteKind.displayName
            notesEmpty.text = "まだ${activeNoteKind.displayName}はありません。\n右下の＋から追加できます。"
            refreshNotes()
        }
    }

    private fun setupWeekdayRow() {
        val row = findViewById<LinearLayout>(R.id.diary_weekday_row)
        listOf("日", "月", "火", "水", "木", "金", "土").forEach { name ->
            val label = MaterialTextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
        }
    }

    private fun setupMonthGrid() {
        monthAdapter = DiaryMonthAdapter { dateKey -> openDate(dateKey) }
        val grid = findViewById<RecyclerView>(R.id.recycler_diary_month)
        grid.layoutManager = GridLayoutManager(this, DiaryCalendarPolicy.COLUMNS)
        grid.adapter = monthAdapter
    }

    private fun setupDateList() {
        datesAdapter = DiaryDateAdapter { dateKey -> openDate(dateKey) }
        val recycler = findViewById<RecyclerView>(R.id.recycler_diary_dates)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = datesAdapter
    }

    private fun setupLibraryNotes() {
        notesAdapter = LibraryNoteAdapter(::openNote)
        notesList.layoutManager = LinearLayoutManager(this)
        notesList.adapter = notesAdapter
        addNoteButton.setOnClickListener {
            openNote(LibraryNote(LibraryNoteStore.newId(), activeNoteKind, "", ""))
        }
    }

    private fun shiftMonth(delta: Int) {
        val (year, month) = DiaryCalendarPolicy.shiftMonth(shownYear, shownMonth, delta)
        shownYear = year
        shownMonth = month
        refreshCalendar()
    }

    private fun refresh() {
        refreshCalendar()
        refreshNotes()
        backupStatus.text = backupStatusText()
    }

    private fun refreshCalendar() {
        val lengths = DiaryStore.textLengths(this)
        monthTitle.text = DiaryCalendarPolicy.monthTitle(shownYear, shownMonth)
        monthAdapter.show(shownYear, shownMonth, lengths)
        datesAdapter.submit(
            DiaryStore.savedDates(this).map { date ->
                DiaryDateAdapter.Row(date, DiaryStore.load(this, date).text)
            }
        )
    }

    private fun refreshNotes() {
        if (!::notesAdapter.isInitialized) {
            return
        }
        val notes = LibraryNoteStore.notes(this, activeNoteKind)
        notesAdapter.submitList(notes)
        notesEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        notesList.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun backupStatusText(): String {
        val name = LibraryBackupSync.selectedName(this)
            ?: return "バックアップ先: 未設定（保存先を選択してください）"
        val last = LibraryBackupSync.lastBackupMs(this)
        val error = LibraryBackupSync.lastError(this)
        val status = when {
            error != null -> "バックアップ先: $name（更新に失敗）"
            last == 0L -> "バックアップ先: $name（未更新）"
            else -> "バックアップ先: $name（最終更新 ${DiaryStore.displayDate(dateOf(last))}）"
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
        val (_, view) = Md3PopupDialog.inflate(this, R.layout.dialog_library_backup)
        val hasBackup = LibraryBackupSync.selectedUri(this) != null
        view.findViewById<MaterialTextView>(R.id.tv_backup_dialog_status).text = backupStatusText()
        view.findViewById<MaterialButton>(R.id.btn_backup_choose).text =
            if (hasBackup) "保存先ファイルを変更" else "保存先ファイルを選択"
        view.findViewById<MaterialButton>(R.id.btn_backup_now).visibility =
            if (hasBackup) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btn_backup_disconnect).visibility =
            if (hasBackup) View.VISIBLE else View.GONE

        val dialog = Md3PopupDialog.show(this, view)
        view.findViewById<MaterialButton>(R.id.btn_backup_choose).setOnClickListener {
            dialog.dismiss()
            showBackupFileOptions()
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
            .setTitle("バックアップファイル")
            .setItems(arrayOf("新しいバックアップを作成", "既存のバックアップを選択")) { _, which ->
                if (which == 0) launchCreateBackupPicker() else launchOpenBackupPicker()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun launchCreateBackupPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = LibraryBackupArchive.MIME_TYPE
            putExtra(Intent.EXTRA_TITLE, LibraryBackupArchive.SUGGESTED_FILE_NAME)
            addFlags(safGrantFlags())
        }
        try {
            createBackupFile.launch(intent)
        } catch (error: Exception) {
            showBackupOutcome(LibraryBackupSync.Outcome(false, error.localizedMessage ?: "ファイル選択画面を開けませんでした。"))
        }
    }

    private fun launchOpenBackupPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // ProviderによってZIPのMIME登録が異なるため、候補は広く表示して中身を検証する。
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            addFlags(safGrantFlags())
        }
        try {
            openBackupFile.launch(intent)
        } catch (error: Exception) {
            showBackupOutcome(LibraryBackupSync.Outcome(false, error.localizedMessage ?: "ファイル選択画面を開けませんでした。"))
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
            showBackupOutcome(LibraryBackupSync.Outcome(false, "選択したファイルの場所を取得できませんでした。"))
            return
        }
        val grantFlags = data.flags
        lifecycleScope.launch {
            val outcome = LibraryBackupSync.connect(this@DiaryCalendarActivity, uri, grantFlags, isNewFile)
            refresh()
            showBackupOutcome(outcome)
        }
    }

    private fun runManualBackup() {
        lifecycleScope.launch {
            val outcome = LibraryBackupSync.backupNow(this@DiaryCalendarActivity)
            refresh()
            showBackupOutcome(outcome)
        }
    }

    private fun showBackupOutcome(outcome: LibraryBackupSync.Outcome) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (outcome.success) "バックアップ" else "バックアップに失敗しました")
            .setMessage(outcome.message)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun confirmDisconnectBackup() {
        MaterialAlertDialogBuilder(this)
            .setTitle("保存先の設定を解除しますか？")
            .setMessage("設定とアクセス権だけを解除します。バックアップファイル自体は削除されません。")
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("解除") { _, _ ->
                lifecycleScope.launch {
                    LibraryBackupSync.disconnect(this@DiaryCalendarActivity)
                    refresh()
                }
            }
            .show()
    }

    private fun openDate(dateKey: String) {
        startActivity(
            Intent(this, DiaryEditorActivity::class.java)
                .putExtra(DiaryEditorActivity.EXTRA_DATE, dateKey)
        )
    }

    private fun openNote(note: LibraryNote) {
        startActivity(
            Intent(this, LibraryNoteEditorActivity::class.java)
                .putExtra(LibraryNoteEditorActivity.EXTRA_KIND, note.kind.archiveKey)
                .putExtra(LibraryNoteEditorActivity.EXTRA_ID, note.id)
        )
    }

    companion object {
        private const val LIBRARY_PREFS = "my_library_ui"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val TAB_DIARY = 0
        private const val TAB_ESSAY = 1
        private const val TAB_WISH_LIST = 2
    }
}
