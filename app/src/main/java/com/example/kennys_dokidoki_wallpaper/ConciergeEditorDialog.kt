package com.example.kennys_dokidoki_wallpaper

import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 下書き専用。永続化履歴とは混ぜず、元画面の「保存」に委ねる。 */
internal object ConciergeEditorDialog {
    fun show(
        activity: AppCompatActivity,
        read: () -> ConciergeEditorPolicy.Draft?,
        apply: (ConciergeEditorPolicy.Draft) -> Unit
    ) {
        val initial = read() ?: return
        val (_, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_concierge_editor)
        val transcript = view.findViewById<TextView>(R.id.tv_editor_conversation)
        val input = view.findViewById<EditText>(R.id.et_editor_request)
        val send = view.findViewById<MaterialButton>(R.id.btn_editor_request)
        val progress = view.findViewById<View>(R.id.progress_editor_concierge)
        view.findViewById<TextView>(R.id.tv_editor_target).text = initial.label
        transcript.text = "この編集欄の内容をもとに変更案を作ります。\n例：このプロンプトをもっと柔らかい雰囲気にして。\n反映後、元の編集画面で保存してください。"
        val history = mutableListOf<String>()
        var job: Job? = null
        var comparison: androidx.appcompat.app.AlertDialog? = null
        val dialog = Md3PopupDialog.show(activity, view)
        dialog.setOnDismissListener {
            job?.cancel()
            comparison?.dismiss()
        }
        send.setOnClickListener {
            val wish = input.text.toString().trim()
            val before = read() ?: return@setOnClickListener
            if (wish.isEmpty() || job?.isActive == true) {
                return@setOnClickListener
            }
            view.findViewById<TextView>(R.id.tv_editor_target).text = before.label
            send.isEnabled = false
            progress.visibility = View.VISIBLE
            transcript.append("\n\nあなた：$wish")
            input.text.clear()
            job = activity.lifecycleScope.launch {
                try {
                    val proposal = withContext(Dispatchers.IO) {
                        ConciergeEditorTools.propose(activity.applicationContext, before, wish, history.takeLast(JevConciergePolicy.HISTORY_TURNS).joinToString("\n"))
                    }
                    if (!dialog.isShowing || read() == null) {
                        return@launch
                    }
                    val after = proposal.draft
                    val warning = if (proposal.confidence < JevConciergePolicy.VERIFY_MIN_PROB) {
                        "要確認：依頼への適合度が低い変更案です。\n\n"
                    } else {
                        ""
                    }
                    val message = buildString {
                        append(warning)
                        append("本文（変更前）\n${before.main}\n\n本文（変更後）\n${after.main}")
                        if (before.kind == ConciergeEditorPolicy.Kind.CARD) {
                            append("\n\nネガティブ（変更前）\n${before.negative}\n\nネガティブ（変更後）\n${after.negative}")
                        }
                    }
                    transcript.append("\nコンシェルジュ：変更案を確認してください。")
                    comparison = MaterialAlertDialogBuilder(Md3PopupDialog.wrap(activity))
                        .setTitle(before.label)
                        .setMessage(message)
                        .setNegativeButton("反映しない") { _, _ ->
                            history.add("依頼：$wish\n変更案は反映しなかった。")
                            transcript.append("\n変更案は反映していません。")
                        }
                        .setPositiveButton("編集欄へ反映") { _, _ ->
                            if (ConciergeEditorPolicy.canApply(before, read())) {
                                apply(after)
                                history.add("依頼：$wish\n反映済み本文：${after.main}\nネガティブ：${after.negative}")
                                transcript.append("\n編集欄へ反映しました。続けて調整できます。保存は元の画面で行ってください。")
                            } else {
                                Toast.makeText(activity, "編集欄が変わりました。上書きせず、もう一度依頼してください。", Toast.LENGTH_LONG).show()
                            }
                        }
                        .show()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    transcript.append("\n変更できませんでした：${error.message}")
                } finally {
                    send.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }
}
