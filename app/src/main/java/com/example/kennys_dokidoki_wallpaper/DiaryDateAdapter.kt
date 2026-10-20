package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView

/** 記録のある日付の一覧。1行タップでその日の日記へ */
class DiaryDateAdapter(
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<DiaryDateAdapter.DateHolder>() {

    data class Row(val dateKey: String, val text: String)

    private var rows: List<Row> = emptyList()

    class DateHolder(view: View) : RecyclerView.ViewHolder(view) {
        val date: MaterialTextView = view.findViewById(R.id.tv_diary_date)
        val preview: MaterialTextView = view.findViewById(R.id.tv_diary_preview)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_diary_date, parent, false)
        return DateHolder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: DateHolder, position: Int) {
        val row = rows[position]
        holder.date.text = DiaryStore.displayDate(row.dateKey)
        holder.preview.text = row.text.ifBlank { "（画像のみ）" }
        holder.itemView.setOnClickListener { onPick(row.dateKey) }
    }
}
