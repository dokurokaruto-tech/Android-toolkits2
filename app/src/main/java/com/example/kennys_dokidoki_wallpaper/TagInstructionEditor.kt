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
 * 指示プリセットを、AI設定の画面から編集・削除する。
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
                .setTitle(TagAiGenerateCopy.LIST_TITLE)
                .setItems(names) { _, which ->
                    showEditor(activity, profiles, which, persist = { persist() }, back = { showList() })
                }
                .setPositiveButton(TagAiGenerateCopy.ADD_PRESET) { _, _ ->
                    showEditor(activity, profiles, index = null, persist = { persist() }, back = { showList() })
                }
                .setNegativeButton(TagAiGenerateCopy.CLOSE, null)
                .show()
        }

        showList()
    }

    fun delete(
        activity: AppCompatActivity,
        selectedName: String?,
        onChanged: () -> Unit
    ) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val profiles = TagInstructionPolicy.parse(
            prefs.getString(TagInstructionPolicy.PROFILES_KEY, null),
            prefs.getString(TagInstructionPolicy.LEGACY_KEY, null)
        )
        if (!TagInstructionPolicy.canDelete(profiles)) {
            Toast.makeText(activity, TagAiGenerateCopy.DELETE_LAST_BLOCKED, Toast.LENGTH_SHORT).show()
            return
        }
        val index = TagInstructionPolicy.selectedIndex(profiles, selectedName)
        val target = profiles[index]
        MaterialAlertDialogBuilder(
            activity,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(TagAiGenerateCopy.DELETE_CONFIRM_TITLE)
            .setMessage(target.name)
            .setNegativeButton(TagAiGenerateCopy.BACK, null)
            .setPositiveButton(TagAiGenerateCopy.DELETE_CONFIRM) { _, _ ->
                prefs.edit()
                    .putString(
                        TagInstructionPolicy.PROFILES_KEY,
                        TagInstructionPolicy.encode(TagInstructionPolicy.removeAt(profiles, index))
                    )
                    .apply()
                Toast.makeText(activity, TagAiGenerateCopy.DELETED, Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .show()
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
            hint = TagAiGenerateCopy.NAME_HINT
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
            hint = TagAiGenerateCopy.BODY_HINT
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
            .setTitle(if (existing == null) TagAiGenerateCopy.EDITOR_NEW else TagAiGenerateCopy.EDITOR_EDIT)
            .setView(container)
            .setPositiveButton(TagAiGenerateCopy.SAVE) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty().ifEmpty { "無題" }
                val content = contentField.text?.toString().orEmpty()
                val next = TagInstructionPolicy.Profile(name, content)
                if (index == null) profiles.add(next) else profiles[index] = next
                persist()
                Toast.makeText(activity, TagAiGenerateCopy.SAVED, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(TagAiGenerateCopy.BACK) { _, _ -> back() }

        if (existing != null && TagInstructionPolicy.canDelete(profiles)) {
            builder.setNeutralButton(TagAiGenerateCopy.DELETE) { _, _ ->
                profiles.removeAt(index!!)
                persist()
                Toast.makeText(activity, TagAiGenerateCopy.DELETED, Toast.LENGTH_SHORT).show()
                back()
            }
        }
        builder.show()
    }
}
