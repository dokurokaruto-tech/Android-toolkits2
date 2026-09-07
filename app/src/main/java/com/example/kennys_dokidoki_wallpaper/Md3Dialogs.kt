package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * MD3 準拠のダイアログを 1 行で出すためのヘルパ。
 *
 * 旧コードの
 *   AlertDialog.Builder(this, R.style.Theme_Kennys_dokidoki_wallpaper)
 *       .setView(EditText(this).apply { setTextColor(Color.WHITE); setPadding(48, 16, 48, 0) })
 * を置き換える。テーマは Theme.Kennys の materialAlertDialogTheme から自動で当たる。
 */
object Md3Dialogs {

    /** 1 行 / 複数行 / 秘密情報 のテキスト入力 */
    fun textInput(
        context: Context,
        title: CharSequence,
        initial: String? = null,
        hint: CharSequence? = null,
        secret: Boolean = false,
        multiline: Boolean = false,
        positiveText: CharSequence = context.getString(R.string.action_save),
        onResult: (String) -> Unit
    ): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_text_input, null, false)
        val layout = view.findViewById<TextInputLayout>(R.id.til_input)
        val editText = view.findViewById<TextInputEditText>(R.id.et_input)

        layout.hint = hint
        when {
            secret -> {
                layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            multiline -> {
                editText.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                editText.minLines = 6
                editText.maxLines = 14
                editText.gravity = Gravity.TOP or Gravity.START
            }
        }
        editText.setText(initial)
        editText.setSelection(editText.text?.length ?: 0)

        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positiveText) { _, _ -> onResult(editText.text?.toString().orEmpty().trim()) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 削除 / 破棄。positive ボタンが error 色になる */
    fun confirmDestructive(
        context: Context,
        title: CharSequence,
        message: CharSequence?,
        positiveText: CharSequence = context.getString(R.string.action_delete),
        onConfirm: () -> Unit
    ): AlertDialog {
        return MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Kennys_Dialog_Destructive)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 単一選択リスト */
    fun pickOne(
        context: Context,
        title: CharSequence,
        items: List<CharSequence>,
        onPick: (index: Int) -> Unit
    ): AlertDialog {
        return MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * PC 生成エージェント接続設定。URL は AgentNetworkPolicy で検証し、
     * 不正なら TextInputLayout.error に理由を出してダイアログを閉じない。
     * API キーは SecurePrefs に保存する。
     */
    fun agentConnection(
        context: Context,
        onSaved: (() -> Unit)? = null,
        onTest: (() -> Unit)? = null
    ): AlertDialog {
        val prefs = context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_agent_connection, null, false)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.til_agent_url)
        val altLayout = view.findViewById<TextInputLayout>(R.id.til_agent_alt)
        val urlField = view.findViewById<TextInputEditText>(R.id.et_agent_url)
        val altField = view.findViewById<TextInputEditText>(R.id.et_agent_alt)
        val keyField = view.findViewById<TextInputEditText>(R.id.et_agent_key)

        urlField.setText(prefs.getString(PrefKeys.AGENT_URL, ""))
        altField.setText(prefs.getString(PrefKeys.AGENT_URL_ALTS, ""))
        keyField.setText(AppSecrets.agentApiKey(context).orEmpty())

        fun validateAndSave(): Boolean {
            urlLayout.error = null
            altLayout.error = null

            val primary = when (val result = AgentNetworkPolicy.validate(urlField.text?.toString().orEmpty())) {
                is AgentNetworkPolicy.Result.Ok -> result.normalizedUrl
                is AgentNetworkPolicy.Result.Rejected -> {
                    urlLayout.error = result.reason
                    return false
                }
            }

            val alts = ArrayList<String>()
            for (raw in altField.text?.toString().orEmpty().split(',', ';', '\n', ' ')) {
                if (raw.isBlank()) {
                    continue
                }
                when (val result = AgentNetworkPolicy.validate(raw)) {
                    is AgentNetworkPolicy.Result.Ok -> alts.add(result.normalizedUrl)
                    is AgentNetworkPolicy.Result.Rejected -> {
                        altLayout.error = result.reason
                        return false
                    }
                }
            }

            prefs.edit()
                .putString(PrefKeys.AGENT_URL, primary)
                .putString(PrefKeys.AGENT_URL_ALTS, alts.joinToString(","))
                .remove(PrefKeys.AGENT_URL_LAST_GOOD)
                .apply()
            AppSecrets.setAgentApiKey(context, keyField.text?.toString())
            return true
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.agent_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.action_save, null)
            .setNeutralButton(R.string.action_test_connection, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // 自動 dismiss を止めて検証エラー時はダイアログを維持する
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validateAndSave()) {
                    onSaved?.invoke()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (validateAndSave()) {
                    onTest?.invoke()
                }
            }
        }
        dialog.show()
        return dialog
    }

    /** Toast の代わり。anchor は Snackbar を出す画面のルート View */
    fun snackbar(anchor: View, message: CharSequence, actionText: CharSequence? = null, action: (() -> Unit)? = null) {
        val bar = Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT)
        if (actionText != null && action != null) {
            bar.setAction(actionText) { action() }
        }
        bar.show()
    }
}
