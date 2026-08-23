package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * タグ文章用の指示書を、アプリ内で編集する。
 */
object TagInstructionEditor {
    fun show(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val profiles = TagInstructionPolicy.parse(
            prefs.getString(TagInstructionPolicy.PROFILES_KEY, null),
            prefs.getString(TagInstructionPolicy.LEGACY_KEY, null)
        ).toMutableList()

        fun persist() {
            prefs.edit()
                .putString(TagInstructionPolicy.PROFILES_KEY, TagInstructionPolicy.encode(profiles))
                .apply()
            onChanged()
        }

        fun showList() {
            val names = profiles.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(
                activity,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle("タグ文章の指示書")
                .setItems(names) { _, which ->
                    showEditor(activity, profiles, which, persist = { persist() }, back = { showList() })
                }
                .setPositiveButton("新規作成") { _, _ ->
                    showEditor(activity, profiles, index = null, persist = { persist() }, back = { showList() })
                }
                .setNegativeButton("閉じる", null)
                .show()
        }

        showList()
    }

    private fun showEditor(
        activity: AppCompatActivity,
        profiles: MutableList<TagInstructionPolicy.Profile>,
        index: Int?,
        persist: () -> Unit,
        back: () -> Unit
    ) {
        val existing = index?.let { profiles.getOrNull(it) }
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameLayout = TextInputLayout(
            activity,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "プロファイル名"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val nameField = TextInputEditText(nameLayout.context).apply {
            setText(existing?.name.orEmpty())
            setSingleLine()
        }
        nameLayout.addView(nameField)
        val contentLayout = TextInputLayout(
            activity,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = "指示書"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        }
        val contentField = TextInputEditText(contentLayout.context).apply {
            setText(existing?.content ?: TagInstructionPolicy.DEFAULT_PROMPT)
            minLines = 10
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        contentLayout.addView(contentField)
        container.addView(nameLayout)
        container.addView(contentLayout)

        val builder = MaterialAlertDialogBuilder(
            activity,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(if (existing == null) "指示書を追加" else "指示書を編集")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty().ifEmpty { "無題" }
                val content = contentField.text?.toString().orEmpty()
                val next = TagInstructionPolicy.Profile(name, content)
                if (index == null) profiles.add(next) else profiles[index] = next
                persist()
                Toast.makeText(activity, "指示書を保存した。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("戻る") { _, _ -> back() }

        if (existing != null && TagInstructionPolicy.canDelete(profiles)) {
            builder.setNeutralButton("削除") { _, _ ->
                profiles.removeAt(index!!)
                persist()
                Toast.makeText(activity, "指示書を消した。", Toast.LENGTH_SHORT).show()
                back()
            }
        }
        builder.show()
    }
}
