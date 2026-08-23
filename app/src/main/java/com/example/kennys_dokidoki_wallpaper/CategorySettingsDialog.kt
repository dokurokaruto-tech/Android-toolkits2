package com.example.kennys_dokidoki_wallpaper

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

object CategorySettingsDialog {
    fun show(
        activity: AppCompatActivity,
        category: String,
        title: String,
        deleteMessage: String,
        onSave: (String) -> Unit,
        onDelete: () -> Unit,
        onBulkThumbnails: () -> Unit
    ) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_category_settings)
        val tvTitle = view.findViewById<MaterialTextView>(R.id.tv_category_settings_title)
        val etName = view.findViewById<TextInputEditText>(R.id.et_category_name)
        val btnBulk = view.findViewById<MaterialButton>(R.id.btn_bulk_thumbnails)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btn_delete_category)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btn_cancel)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_save_category)
        tvTitle.text = title
        etName.setText(category)
        val dialog = Md3PopupDialog.show(activity, view)
        btnBulk.setOnClickListener {
            dialog.dismiss()
            onBulkThumbnails()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val newName = etName.text?.toString()?.trim().orEmpty()
            if (newName.isEmpty()) {
                Toast.makeText(activity, "名称を入力してください。", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave(newName)
            dialog.dismiss()
        }
        btnDelete.setOnClickListener {
            AlertDialog.Builder(activity, R.style.Theme_Kennys_dokidoki_wallpaper)
                .setTitle("本当に削除する？")
                .setMessage(deleteMessage)
                .setPositiveButton("削除する") { _, _ ->
                    onDelete()
                    dialog.dismiss()
                }
                .setNegativeButton("やめとく", null)
                .show()
        }
    }
}
