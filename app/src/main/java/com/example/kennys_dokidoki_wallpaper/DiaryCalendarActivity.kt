package com.example.kennys_dokidoki_wallpaper

import android.content.Intent
import android.os.Bundle
import android.widget.CalendarView
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

/** 日記の入口。年月日をカレンダーから選び、その日のメモ帳を開く */
class DiaryCalendarActivity : AppCompatActivity() {

    private lateinit var datesAdapter: DiaryDateAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diary_calendar)

        findViewById<ImageButton>(R.id.btn_diary_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_diary_today).setOnClickListener {
            openDate(DiaryStore.todayKey())
        }

        findViewById<CalendarView>(R.id.diary_calendar)
            .setOnDateChangeListener { _, year, month, day ->
                openDate(DiaryStore.dateKey(year, month + 1, day))
            }

        datesAdapter = DiaryDateAdapter { dateKey -> openDate(dateKey) }
        val recycler = findViewById<RecyclerView>(R.id.recycler_diary_dates)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = datesAdapter
    }

    override fun onResume() {
        super.onResume()
        datesAdapter.submit(DiaryStore.savedDates(this).map { date ->
            DiaryDateAdapter.Row(date, DiaryStore.load(this, date).text)
        })
    }

    private fun openDate(dateKey: String) {
        startActivity(
            Intent(this, DiaryEditorActivity::class.java)
                .putExtra(DiaryEditorActivity.EXTRA_DATE, dateKey)
        )
    }
}
