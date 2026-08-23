package com.example.kennys_dokidoki_wallpaper

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textview.MaterialTextView

object BulkThumbnailDialog {
    fun show(
        activity: AppCompatActivity,
        title: String,
        entries: List<BulkThumbnailPickerPolicy.Entry>,
        onGenerate: (List<String>) -> Unit
    ) {
        if (entries.isEmpty()) {
            Toast.makeText(activity, "このカテゴリーには対象がありません。", Toast.LENGTH_SHORT).show()
            return
        }
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_bulk_thumbnails)
        val tvTitle = view.findViewById<MaterialTextView>(R.id.tv_bulk_title)
        val tvSummary = view.findViewById<MaterialTextView>(R.id.tv_bulk_summary)
        val chips = view.findViewById<ChipGroup>(R.id.cg_bulk_filter)
        val chipAll = view.findViewById<Chip>(R.id.chip_bulk_all)
        val chipMissing = view.findViewById<Chip>(R.id.chip_bulk_missing)
        val chipSet = view.findViewById<Chip>(R.id.chip_bulk_set)
        val recycler = view.findViewById<RecyclerView>(R.id.rv_bulk_thumbnails)
        val btnSelectAll = view.findViewById<MaterialButton>(R.id.btn_bulk_select_all)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_bulk_cancel)
        val btnStart = view.findViewById<MaterialButton>(R.id.btn_bulk_start)

        var rows = entries.toList()
        var filter = BulkThumbnailPickerPolicy.Filter.ALL
        val adapter = BulkThumbnailAdapter { id ->
            rows = rows.map { if (it.id == id) BulkThumbnailPickerPolicy.toggle(it) else it }
            render()
        }

        fun currentFilter(): BulkThumbnailPickerPolicy.Filter = when {
            chipMissing.isChecked -> BulkThumbnailPickerPolicy.Filter.MISSING
            chipSet.isChecked -> BulkThumbnailPickerPolicy.Filter.SET
            else -> BulkThumbnailPickerPolicy.Filter.ALL
        }

        fun render() {
            filter = currentFilter()
            tvSummary.text = BulkThumbnailPickerPolicy.summary(rows)
            adapter.submit(BulkThumbnailPickerPolicy.visible(rows, filter))
            val visible = BulkThumbnailPickerPolicy.visible(rows, filter)
            val allOn = visible.isNotEmpty() && visible.all { it.selected }
            btnSelectAll.text = if (allOn) "全解除" else "全選択"
        }

        tvTitle.text = title
        recycler.layoutManager = LinearLayoutManager(view.context)
        recycler.adapter = adapter
        chips.setOnCheckedStateChangeListener { _, _ -> render() }
        btnSelectAll.setOnClickListener {
            val visibleIds = BulkThumbnailPickerPolicy.visible(rows, filter).map { it.id }.toSet()
            val allOn = visibleIds.isNotEmpty() && rows.filter { it.id in visibleIds }.all { it.selected }
            rows = rows.map { if (it.id in visibleIds) it.copy(selected = !allOn) else it }
            render()
        }
        val dialog = Md3PopupDialog.show(activity, view)
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnStart.setOnClickListener {
            val ids = BulkThumbnailPickerPolicy.selectedIds(rows)
            if (ids.isEmpty()) {
                Toast.makeText(activity, "生成する対象を選んでください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onGenerate(ids)
            dialog.dismiss()
        }
        render()
    }
}
