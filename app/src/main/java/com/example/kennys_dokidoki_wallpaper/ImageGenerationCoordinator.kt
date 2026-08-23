package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * 通常生成／再生成の監視を Activity ではなくプロセス寿命で持つ。
 * 閲覧画面で戻っても PiP と生成監視が一緒に死なない。
 */
object ImageGenerationCoordinator {
    private const val TAG = "ImageGen"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    fun isBusy(): Boolean =
        watchJob?.isActive == true || GenerationProgressManager.state.value.isGenerating

    fun start(activity: Activity, requests: List<AgentGenerationRequest>): Boolean {
        val valid = requests.filter { it.prompt.isNotBlank() }
        if (valid.isEmpty()) {
            Toast.makeText(activity, "生成できるプロンプトがありません。", Toast.LENGTH_SHORT).show()
            return false
        }
        if (isBusy()) {
            Toast.makeText(activity, GeneratedImageReplayPolicy.BUSY, Toast.LENGTH_SHORT).show()
            return false
        }
        val app = activity.applicationContext
        GenerationProgressManager.startGeneration(batchMode = valid.size > 1, total = valid.size)
        showPip(activity)
        watchJob = scope.launch {
            try {
                val probe = GenerationAgentClient.probe(app)
                if (probe.code != AgentConnectionClassifier.OK &&
                    probe.code != AgentConnectionClassifier.SD_DOWN
                ) {
                    GenerationProgressManager.endGeneration(force = true)
                    showFailure(activity, app, probe, "PC生成エージェントに接続できない")
                    return@launch
                }
                val accepted = GenerationAgentClient.submit(app, valid)
                val completed = GenerationAgentClient.monitor(app, accepted)
                completed.imageUrls.forEachIndexed { order, url ->
                    val request = valid.getOrNull(order) ?: valid.last()
                    GeneratedImageDraftStore.seedGeneratedSource(
                        app,
                        Uri.parse(url),
                        request.tags,
                        request.cardStates,
                        request.width,
                        request.height,
                        request.steps,
                        request.samplerName,
                        request.prompt,
                        request.randomPickedIds,
                        request.randomEnabledCategories,
                        request.seed,
                        request.negativePrompt
                    )
                }
                Toast.makeText(
                    app,
                    "PC生成完了: ${completed.completed}/${completed.total}枚（閲覧から確認できます）",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e(TAG, "image job failed", error)
                GenerationProgressManager.endGeneration(force = true)
                showFailure(
                    activity,
                    app,
                    AgentConnectionLog.last ?: AgentConnectionClassifier.fromException(error),
                    "PC生成エージェントとの通信に失敗"
                )
            }
        }
        return true
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
