package com.example.kennys_dokidoki_wallpaper

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 「私の図書館」の入口。日記・エッセイ・やりたい事リストを切り替える。 */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var datesAdapter: DiaryDateAdapter
    private lateinit var monthAdapter: DiaryMonthAdapter
    private lateinit var notesAdapter: LibraryNoteAdapter
    private lateinit var monthTitle: MaterialTextView
    private lateinit var driveStatus: MaterialTextView
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
    private var syncInProgress = false
    private var authFlowPending = false

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authFlowPending = false
        // Google Sign-In は失敗時にも RESULT_CANCELED を返すことがあるため、
        // resultCode で早期終了せず Intent から ApiException の理由まで読む。
        val signInResult = GoogleDriveAuth.accountFromResult(result.data)
        val email = signInResult.email
        if (email == null) {
            Toast.makeText(
                this,
                signInResult.errorMessage ?: "Googleアカウントと連携できませんでした。",
                Toast.LENGTH_LONG
            ).show()
            return@registerForActivityResult
        }
        DriveSyncPrefs.link(this, email)
        refresh()
        Toast.makeText(this, "$email と連携しました。", Toast.LENGTH_SHORT).show()
        syncWithDrive(Feedback.SHOW)
    }

    private enum class Feedback { SHOW, SILENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_my_library)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onToolbarMenuItemClick)

        monthTitle = findViewById(R.id.tv_month_title)
        driveStatus = findViewById(R.id.tv_drive_status)
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
        // 開くたびに Drive 側の新しい日記・ノートを取り込む。
        if (!authFlowPending && DiaryDriveSync.isLinked(this)) {
            syncWithDrive(Feedback.SILENT)
        }
    }

    private fun onToolbarMenuItemClick(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_library_drive -> {
            showDriveDialog()
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
        driveStatus.text = driveStatusText()
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

    private fun driveStatusText(): String {
        val account = DriveSyncPrefs.account(this) ?: return "Google Drive: 未連携"
        val last = DriveSyncPrefs.lastSyncMs(this)
        if (last == 0L) {
            return "Google Drive: $account"
        }
        return "Google Drive: $account（最終同期 ${DiaryStore.displayDate(dateOf(last))}）"
    }

    private fun dateOf(epochMs: Long): String {
        val date = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        return DiaryStore.dateKey(date.year, date.monthValue, date.dayOfMonth)
    }

    private fun syncWithDrive(feedback: Feedback) {
        if (syncInProgress) {
            return
        }
        syncInProgress = true
        lifecycleScope.launch {
            val outcome = try {
                DiaryDriveSync.sync(this@DiaryCalendarActivity)
            } finally {
                syncInProgress = false
            }
            refresh()
            if (feedback == Feedback.SILENT) {
                return@launch
            }
            val message = outcome.error
                ?: "同期しました。アップロード${outcome.pushed}件 / 取り込み${outcome.pulled}件"
            Toast.makeText(this@DiaryCalendarActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Drive 連携の状態と操作をまとめた Material 3 ダイアログ */
    private fun showDriveDialog() {
        val (_, view) = Md3PopupDialog.inflate(this, R.layout.dialog_diary_drive)
        val linked = DiaryDriveSync.isLinked(this)
        view.findViewById<MaterialTextView>(R.id.tv_drive_dialog_status).text = driveStatusText()
        view.findViewById<MaterialButton>(R.id.btn_drive_connect).text =
            if (linked) "Googleアカウントを変更" else "Googleアカウントと連携"
        view.findViewById<MaterialButton>(R.id.btn_drive_sync_now).visibility =
            if (linked) View.VISIBLE else View.GONE
        view.findViewById<MaterialButton>(R.id.btn_drive_disconnect).visibility =
            if (linked) View.VISIBLE else View.GONE

        val dialog = Md3PopupDialog.show(this, view)
        view.findViewById<MaterialButton>(R.id.btn_drive_connect).setOnClickListener {
            authFlowPending = true
            try {
                signIn.launch(GoogleDriveAuth.signInIntent(this))
            } catch (error: Exception) {
                authFlowPending = false
                Toast.makeText(this, error.message ?: "Googleアカウント画面を開けませんでした。", Toast.LENGTH_LONG).show()
            }
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_drive_sync_now).setOnClickListener {
            syncWithDrive(Feedback.SHOW)
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_drive_disconnect).setOnClickListener {
            GoogleDriveAuth.signOut(this)
            DriveSyncPrefs.unlink(this)
            refresh()
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_drive_close).setOnClickListener { dialog.dismiss() }
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
                .putExtra(LibraryNoteEditorActivity.EXTRA_KIND, note.kind.driveKey)
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
