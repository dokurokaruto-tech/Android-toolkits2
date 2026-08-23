package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * サムネイル生成を通常生成と同じ PiP 経路で回し、完了したら対象へ自動で載せる。
 * 監視は Activity ではなくプロセス寿命のスコープで行うので、編集画面を閉じても、
 * アプリを最小化しても紐づけが落ちない。
 */
object ThumbnailGenerationCoordinator {
    private const val TAG = "ThumbGen"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    fun isBusy(): Boolean =
        watchJob?.isActive == true || GenerationProgressManager.state.value.isGenerating

    fun start(activity: Activity, items: List<ThumbnailBindPolicy.Item>): Boolean {
        val valid = items.filter { it.target.isValid && it.request.prompt.isNotBlank() }
        if (valid.isEmpty()) {
            Toast.makeText(activity, "サムネイルを作る対象が無い。", Toast.LENGTH_SHORT).show()
            return false
        }
        if (isBusy()) {
            Toast.makeText(activity, "別の生成が終わるまで待ってくれ。", Toast.LENGTH_SHORT).show()
            return false
        }
        val app = activity.applicationContext
        val requests = valid.map { it.request.copy(purpose = "thumbnail") }
        val targets = valid.map { it.target }
        GenerationProgressManager.startGeneration(
            batchMode = requests.size > 1,
            total = requests.size,
            silent = false
        )
        showPip(activity)
        watchJob = scope.launch {
            try {
                val probe = GenerationAgentClient.probe(app, "サムネイル生成")
                if (probe.code != AgentConnectionClassifier.OK &&
                    probe.code != AgentConnectionClassifier.SD_DOWN
                ) {
                    GenerationProgressManager.endGeneration(force = true)
                    showFailure(activity, app, probe, "サムネイル生成の接続失敗")
                    return@launch
                }
                val accepted = GenerationAgentClient.submit(
                    app,
                    requests,
                    persistForReconnect = true,
                    jobKind = ThumbnailBindPolicy.JOB_KIND_THUMBNAIL,
                    thumbnailTargets = targets
                )
                val completed = GenerationAgentClient.monitor(app, accepted, silent = false)
                val bound = ThumbnailBinder.applyCompleted(app, completed.imageUrls, targets)
                Toast.makeText(
                    app,
                    if (bound > 0) "サムネイル ${bound} 枚を紐づけた。"
                    else (completed.error ?: "サムネイルはできたが紐づけ先が無い。"),
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "thumbnail job failed", error)
                GenerationProgressManager.endGeneration(force = true)
                showFailure(
                    activity,
                    app,
                    AgentConnectionLog.last ?: AgentConnectionClassifier.fromException(error),
                    "サムネイル生成の通信失敗"
                )
            }
        }
        return true
    }

    fun ensureWatching(context: Context) {
        if (watchJob?.isActive == true) return
        if (!GenerationAgentClient.hasPendingJob(context)) return
        val app = context.applicationContext
        if (GenerationAgentClient.isThumbnailJob(app) &&
            !GenerationProgressActivity.isPipActive &&
            !GenerationPipExpandPolicy.shouldSuppressPipRelaunch() &&
            context is Activity
        ) {
            showPip(context)
        }
        watchJob = scope.launch {
            try {
                GenerationAgentClient.resumePendingJob(app)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Could not resume thumbnail/image job", error)
                GenerationProgressManager.endGeneration(force = true)
            }
        }
    }

    private fun showPip(activity: Activity) {
        if (GenerationProgressActivity.isPipActive) return
        activity.startActivity(Intent(activity, GenerationProgressActivity::class.java))
    }

    private suspend fun showFailure(
        activity: Activity,
        app: Context,
        diagnosis: AgentConnectionDiagnosis,
        title: String
    ) {
        withContext(Dispatchers.Main) {
            if (!activity.isFinishing) {
                AgentConnectionUi.showDiagnosis(activity, diagnosis, title)
            } else {
                Toast.makeText(app, diagnosis.displayText(), Toast.LENGTH_LONG).show()
            }
        }
    }
}
