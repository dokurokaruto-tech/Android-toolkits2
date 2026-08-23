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
 * プロンプトカード専用の指示プリセットを編集する。タグ側の保存場所は触らない。
 */
object PromptCardInstructionEditor {
    fun show(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val profiles = PromptCardInstructionPolicy.parse(
            prefs.getString(PromptCardInstructionPolicy.PROFILES_KEY, null),
            prefs.getString(PromptCardInstructionPolicy.LEGACY_KEY, null)
        ).toMutableList()

        fun persist() {
            prefs.edit()
                .putString(PromptCardInstructionPolicy.PROFILES_KEY, PromptCardInstructionPolicy.encode(profiles))
                .apply()
            onChanged()
        }

        fun showList() {
            val names = profiles.map { it.name }.toTypedArray()
            MaterialAlertDialogBuilder(
                activity,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
            )
                .setTitle(PromptCardAiCopy.LIST_TITLE)
                .setItems(names) { _, which ->
                    showEditor(activity, profiles, which, persist = { persist() }, back = { showList() })
                }
                .setPositiveButton(PromptCardAiCopy.ADD_PRESET) { _, _ ->
                    showEditor(activity, profiles, index = null, persist = { persist() }, back = { showList() })
                }
                .setNegativeButton(PromptCardAiCopy.CLOSE, null)
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
        val profiles = PromptCardInstructionPolicy.parse(
            prefs.getString(PromptCardInstructionPolicy.PROFILES_KEY, null),
            prefs.getString(PromptCardInstructionPolicy.LEGACY_KEY, null)
        )
        if (!PromptCardInstructionPolicy.canDelete(profiles)) {
            Toast.makeText(activity, PromptCardAiCopy.DELETE_LAST_BLOCKED, Toast.LENGTH_SHORT).show()
            return
        }
        val index = PromptCardInstructionPolicy.selectedIndex(profiles, selectedName)
        val target = profiles[index]
        MaterialAlertDialogBuilder(
            activity,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        )
            .setTitle(PromptCardAiCopy.DELETE_CONFIRM_TITLE)
            .setMessage(target.name)
            .setNegativeButton(PromptCardAiCopy.BACK, null)
            .setPositiveButton(PromptCardAiCopy.DELETE_CONFIRM) { _, _ ->
                prefs.edit()
                    .putString(
                        PromptCardInstructionPolicy.PROFILES_KEY,
                        PromptCardInstructionPolicy.encode(PromptCardInstructionPolicy.removeAt(profiles, index))
                    )
                    .apply()
                Toast.makeText(activity, PromptCardAiCopy.DELETED, Toast.LENGTH_SHORT).show()
                onChanged()
            }
            .show()
    }

    private fun showEditor(
        activity: AppCompatActivity,
        profiles: MutableList<PromptCardInstructionPolicy.Profile>,
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
            hint = PromptCardAiCopy.NAME_HINT
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
            hint = PromptCardAiCopy.BODY_HINT
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * density).toInt() }
        }
        val contentField = TextInputEditText(contentLayout.context).apply {
            setText(existing?.content ?: PromptCardInstructionPolicy.DEFAULT_PROMPT)
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
            .setTitle(if (existing == null) PromptCardAiCopy.EDITOR_NEW else PromptCardAiCopy.EDITOR_EDIT)
            .setView(container)
            .setPositiveButton(PromptCardAiCopy.SAVE) { _, _ ->
                val name = nameField.text?.toString()?.trim().orEmpty().ifEmpty { "無題" }
                val content = contentField.text?.toString().orEmpty()
                val next = PromptCardInstructionPolicy.Profile(name, content)
                if (index == null) profiles.add(next) else profiles[index] = next
                persist()
                Toast.makeText(activity, PromptCardAiCopy.SAVED, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(PromptCardAiCopy.BACK) { _, _ -> back() }

        if (existing != null && PromptCardInstructionPolicy.canDelete(profiles)) {
            builder.setNeutralButton(PromptCardAiCopy.DELETE) { _, _ ->
                profiles.removeAt(index!!)
                persist()
                Toast.makeText(activity, PromptCardAiCopy.DELETED, Toast.LENGTH_SHORT).show()
                back()
            }
        }
        builder.show()
    }
}
