package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * チャット設定ボトムシート。ChatOverlayActivity.showChatSettingsDialog() の置き換え。
 *
 *   ChatSettingsSheet.show(supportFragmentManager, sessionId, sessionName, isTemporary, engine)
 *
 * Activity 側は [Host] を実装し、既存の linkSessionToSet / handleResetChat /
 * showEngineSelectionDialog を各コールバックから呼ぶだけでよい。
 *
 * 旧実装の問題:
 *   - LinearLayout + GradientDrawable + Color.parseColor("#1C1B1F") で疑似 MD3 を手描き
 *   - "🗑️ この会話を破棄する" が確認なしで即実行
 *   - セッションの「一時」状態を名前の "⏳ " 接頭辞で判定
 */
class ChatSettingsSheet : BottomSheetDialogFragment() {

    interface Host {
        fun onChatSessionRenamed(sessionId: String, newName: String)
        fun onLinkSessionToSet(sessionId: String)
        fun onDiscardChat()
        fun onPickEngine()
    }

    companion object {
        const val TAG = "ChatSettingsSheet"
        private const val ARG_SESSION_ID = "session_id"
        private const val ARG_SESSION_NAME = "session_name"
        private const val ARG_IS_TEMPORARY = "is_temporary"
        private const val ARG_ENGINE = "engine"

        fun newInstance(sessionId: String?, sessionName: String, isTemporary: Boolean, engine: String): ChatSettingsSheet {
            return ChatSettingsSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SESSION_ID, sessionId)
                    putString(ARG_SESSION_NAME, sessionName)
                    putBoolean(ARG_IS_TEMPORARY, isTemporary)
                    putString(ARG_ENGINE, engine)
                }
            }
        }

        fun show(
            manager: androidx.fragment.app.FragmentManager,
            sessionId: String?,
            sessionName: String,
            isTemporary: Boolean,
            engine: String
        ) {
            if (manager.findFragmentByTag(TAG) != null) {
                return
            }
            newInstance(sessionId, sessionName, isTemporary, engine).show(manager, TAG)
        }
    }

    private var host: Host? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        host = context as? Host
    }

    override fun onDetach() {
        host = null
        super.onDetach()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.sheet_chat_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val sessionId = args.getString(ARG_SESSION_ID)
        val isTemporary = args.getBoolean(ARG_IS_TEMPORARY)
        val engine = args.getString(ARG_ENGINE) ?: ChatGenerationManager.ENGINE_CLOUD

        val status = view.findViewById<TextView>(R.id.tv_session_status)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.til_session_name)
        val nameField = view.findViewById<TextInputEditText>(R.id.et_session_name)
        val saveButton = view.findViewById<MaterialButton>(R.id.btn_save_name)
        val engineValue = view.findViewById<TextView>(R.id.tv_engine_value)

        status.setText(if (isTemporary) R.string.chat_status_temporary else R.string.chat_status_linked)
        nameField.setText(args.getString(ARG_SESSION_NAME))
        saveButton.setText(if (isTemporary) R.string.chat_save_name else R.string.chat_update_name)
        engineValue.setText(
            if (engine == ChatGenerationManager.ENGINE_LOCAL) R.string.chat_engine_local else R.string.chat_engine_cloud
        )

        saveButton.setOnClickListener {
            val newName = nameField.text?.toString().orEmpty().trim()
            if (newName.isEmpty()) {
                nameLayout.error = getString(R.string.chat_name_required)
                return@setOnClickListener
            }
            nameLayout.error = null
            if (sessionId != null) {
                ChatSessionManager.renameSession(requireContext(), sessionId, newName)
                host?.onChatSessionRenamed(sessionId, newName)
            }
            dismiss()
        }

        view.findViewById<View>(R.id.row_engine).setOnClickListener {
            dismiss()
            host?.onPickEngine()
        }

        view.findViewById<View>(R.id.row_link_set).setOnClickListener {
            if (sessionId != null) {
                host?.onLinkSessionToSet(sessionId)
            }
            dismiss()
        }

        view.findViewById<View>(R.id.btn_discard).setOnClickListener {
            Md3Dialogs.confirmDestructive(
                requireContext(),
                getString(R.string.chat_discard_confirm_title),
                getString(R.string.chat_discard_confirm_message),
                getString(R.string.chat_discard)
            ) {
                host?.onDiscardChat()
                dismiss()
            }
        }
    }
}
