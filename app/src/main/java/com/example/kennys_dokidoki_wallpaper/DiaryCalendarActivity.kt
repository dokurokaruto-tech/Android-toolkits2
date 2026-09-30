package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 日記の入口。年月日をカレンダーから選び、その日のメモ帳を開く。
 * 書いた日は緑で塗られ、文字数が多いほど濃くなる。
 */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var datesAdapter: DiaryDateAdapter
    private lateinit var monthAdapter: DiaryMonthAdapter
    private lateinit var monthTitle: TextView
    private lateinit var driveStatus: TextView

    private var shownYear = 0
    private var shownMonth = 0

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }
        val email = GoogleDriveAuth.accountFromResult(result.data)
        if (email == null) {
            Toast.makeText(this, "連携できませんでした。", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        DriveSyncPrefs.link(this, email)
        Toast.makeText(this, "$email と連携しました。", Toast.LENGTH_SHORT).show()
        syncWithDrive(Feedback.SHOW)
    }

    private enum class Feedback { SHOW, SILENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        monthTitle = findViewById(R.id.tv_month_title)
        driveStatus = findViewById(R.id.tv_drive_status)

        findViewById<ImageButton>(R.id.btn_diary_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_diary_drive).setOnClickListener { showDriveDialog() }
        findViewById<MaterialButton>(R.id.btn_diary_today).setOnClickListener {
            openDate(DiaryStore.todayKey())
        }
        findViewById<ImageButton>(R.id.btn_month_prev).setOnClickListener { shiftMonth(-1) }
        findViewById<ImageButton>(R.id.btn_month_next).setOnClickListener { shiftMonth(1) }

        setupWeekdayRow()
        setupMonthGrid()
        setupDateList()

        val today = LocalDate.now()
        shownYear = today.year
        shownMonth = today.monthValue
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 開くたびに Drive 側の新しい記録を取り込む
        if (DiaryDriveSync.isLinked(this)) {
            syncWithDrive(Feedback.SILENT)
        }
    }

    private fun setupWeekdayRow() {
        val row = findViewById<LinearLayout>(R.id.diary_weekday_row)
        listOf("日", "月", "火", "水", "木", "金", "土").forEach { name ->
            val label = TextView(this).apply {
                text = name
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(getColor(android.R.color.darker_gray))
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

    private fun shiftMonth(delta: Int) {
        val (year, month) = DiaryCalendarPolicy.shiftMonth(shownYear, shownMonth, delta)
        shownYear = year
        shownMonth = month
        refresh()
    }

    private fun refresh() {
        val lengths = DiaryStore.textLengths(this)
        monthTitle.text = DiaryCalendarPolicy.monthTitle(shownYear, shownMonth)
        monthAdapter.show(shownYear, shownMonth, lengths)
        datesAdapter.submit(
            DiaryStore.savedDates(this).map { date ->
                DiaryDateAdapter.Row(date, DiaryStore.load(this, date).text)
            }
        )
        driveStatus.text = driveStatusText()
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
        lifecycleScope.launch {
            val outcome = DiaryDriveSync.sync(this@DiaryCalendarActivity)
            refresh()
            if (feedback == Feedback.SILENT) {
                return@launch
            }
            val message = outcome.error
                ?: "同期しました。アップロード${outcome.pushed}件 / 取り込み${outcome.pulled}件"
            Toast.makeText(this@DiaryCalendarActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Drive 連携の状態と操作をまとめたポップアップ */
    private fun showDriveDialog() {
        val (_, view) = Md3PopupDialog.inflate(this, R.layout.dialog_diary_drive)
        view.findViewById<TextView>(R.id.tv_drive_dialog_status).text = driveStatusText()

        val dialog = Md3PopupDialog.show(this, view)
        view.findViewById<MaterialButton>(R.id.btn_drive_connect).setOnClickListener {
            signIn.launch(GoogleDriveAuth.signInIntent(this))
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
}
