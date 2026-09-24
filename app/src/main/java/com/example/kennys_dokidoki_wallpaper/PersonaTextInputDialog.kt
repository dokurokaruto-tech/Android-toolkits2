package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.text.InputType
import android.view.WindowManager
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

/** 1つだけ文字を入れる MD3 ポップアップ。カテゴリー名や本文の即編集に使う。 */
object PersonaTextInputDialog {

    /** 名前は1行で足りる。本文は改行を許す。 */
    enum class Mode { NAME, BODY }

    fun show(
        activity: Activity,
        title: String,
        hint: String,
        initial: String,
        mode: Mode,
        onConfirm: (String) -> Unit
    ) {
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_persona_text_input)
        view.findViewById<MaterialTextView>(R.id.tv_persona_input_title).text = title
        view.findViewById<MaterialTextView>(R.id.tv_persona_input_hint).text = hint

        val input = view.findViewById<TextInputEditText>(R.id.et_persona_input)
        val layout = view.findViewById<TextInputLayout>(R.id.til_persona_input)
        input.inputType = when (mode) {
            Mode.NAME -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            Mode.BODY -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        input.setText(initial)
        input.setSelection(input.text?.length ?: 0)

        input.doAfterTextChanged { layout.error = null }

        val dialog = Md3PopupDialog.showCompact(activity, view)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        view.findViewById<MaterialButton>(R.id.btn_persona_input_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_persona_input_save).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                layout.error = activity.getString(R.string.persona_input_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            onConfirm(text)
        }
    }
}
