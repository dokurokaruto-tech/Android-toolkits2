package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 元の監視・停止フラグには触れず、同じPCジョブへ優先タスクを追加する。 */
object GenerationInsertionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sending = false

    fun offer(
        activity: Activity,
        requests: List<AgentGenerationRequest>,
        targets: List<ThumbnailBindPolicy.Target> = emptyList()
    ): Boolean {
        if (sending) {
            Toast.makeText(activity, "割り込みを送信中です。", Toast.LENGTH_SHORT).show()
            return false
        }
        if (GenerationAgentClient.hasPendingInsertion(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("未確認の割り込みがあります")
                .setMessage("前回の依頼を同じIDで再送します。今回の新しい設定は送信しません。記録を破棄しても、PCで受理済みの生成は取り消されません。")
                .setPositiveButton("前回分を再送") { _, _ -> send(activity, null, emptyList()) }
                .setNeutralButton("記録を破棄") { _, _ -> GenerationAgentClient.discardInsertion(activity) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return true
        }
        if (!GenerationAgentClient.hasPendingJob(activity)) {
            Toast.makeText(activity, "PCの受理待ちです。少し待って再試行してください。", Toast.LENGTH_LONG).show()
            return false
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("次の生成に割り込み")
            .setMessage("生成中の1枚はそのまま完成させ、通常の待機分より先に${requests.size}枚を生成します。総枚数は${requests.size}枚増えます。先に受理された割り込みがある場合は、その後に続きます。")
            .setPositiveButton("割り込みを追加") { _, _ -> send(activity, requests, targets) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    private fun send(
        activity: Activity,
        requests: List<AgentGenerationRequest>?,
        targets: List<ThumbnailBindPolicy.Target>
    ) {
        if (sending) {
            return
        }
        sending = true
        val app = activity.applicationContext
        scope.launch {
            try {
                val accepted = if (requests == null) {
                    GenerationAgentClient.retryInsertion(app)
                } else {
                    GenerationAgentClient.insertNext(app, requests, targets)
                }
                if (accepted != null) {
                    if (GenerationAgentClient.activeJobId(app) == accepted.id && !accepted.isTerminal) {
                        GenerationProgressManager.updateBatchProgress(
                            (accepted.completed + accepted.failed + 1).coerceAtMost(accepted.total),
                            accepted.total, accepted.completed
                        )
                    }
                    if (!accepted.isTerminal && !GenerationProgressManager.state.value.isGenerating) {
                        ThumbnailGenerationCoordinator.ensureWatching(app)
                    }
                    Toast.makeText(app, "割り込みを受理しました。総枚数: ${accepted.total}枚", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                Toast.makeText(app, "割り込みを確認できませんでした。PCエージェントの更新・接続を確認し、再送してください。\n${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                sending = false
            }
        }
    }
}
