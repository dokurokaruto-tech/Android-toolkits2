package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 1か月分の升目。日記のある日は緑で塗り、文字数が多いほど濃くする。
 */
class DiaryMonthAdapter(
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<DiaryMonthAdapter.DayHolder>() {

    private var year = 0
    private var month = 0
    private var cells: List<Int?> = emptyList()
    private var charCounts: Map<String, Int> = emptyMap()

    class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fill: View = view.findViewById(R.id.v_day_fill)
        val number: TextView = view.findViewById(R.id.tv_day_number)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun show(year: Int, month: Int, charCounts: Map<String, Int>) {
        this.year = year
        this.month = month
        this.cells = DiaryCalendarPolicy.monthCells(year, month)
        this.charCounts = charCounts
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diary_day, parent, false)
        return DayHolder(view)
    }

    override fun getItemCount(): Int = cells.size

    override fun onBindViewHolder(holder: DayHolder, position: Int) {
        val day = cells[position]
        if (day == null) {
            holder.number.text = ""
            holder.fill.alpha = 0f
            holder.number.setOnClickListener(null)
            return
        }

        val dateKey = DiaryStore.dateKey(year, month, day)
        holder.number.text = day.toString()
        holder.fill.alpha = DiaryCalendarPolicy.greenAlpha(charCounts[dateKey] ?: 0)
        holder.number.setOnClickListener { onPick(dateKey) }
    }
}
