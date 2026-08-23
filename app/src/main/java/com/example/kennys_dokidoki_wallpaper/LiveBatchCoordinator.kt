package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 通常バッチの未着手枚だけ、今のビルダー状態で差し替える。
 * サムネイルジョブと再生成は触らない。
 */
object LiveBatchCoordinator {
    private const val TAG = "LiveBatch"
    const val DEBOUNCE_MS = 250L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private var jobId: String? = null
    private var prepared: List<GeneratedImageTagBinding.PreparedImage> = emptyList()
    private var fingerprint: String? = null
    private var debounceJob: Job? = null

    fun currentPrepared(): List<GeneratedImageTagBinding.PreparedImage> =
        synchronized(lock) { prepared.toList() }

    fun recordInitial(
        jobId: String,
        prepared: List<GeneratedImageTagBinding.PreparedImage>,
        fingerprint: String
    ) {
        synchronized(lock) {
            this.jobId = jobId
            this.prepared = prepared.toList()
            this.fingerprint = fingerprint
        }
    }

    fun clear(jobId: String? = this.jobId) {
        synchronized(lock) {
            if (jobId != null && this.jobId != null && this.jobId != jobId) return
            this.jobId = null
            prepared = emptyList()
            fingerprint = null
            debounceJob?.cancel()
            debounceJob = null
        }
    }

    fun scheduleFromBuilder(
        context: Context,
        snapshotForPending: (Int) -> GeneratedImageTagBinding.Snapshot
    ) {
        val app = context.applicationContext
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            refreshNow(app, snapshotForPending)
        }
    }

    private suspend fun refreshNow(
        context: Context,
        snapshotForPending: (Int) -> GeneratedImageTagBinding.Snapshot
    ) {
        if (!GenerationAgentClient.hasPendingJob(context)) return
        if (GenerationAgentClient.isThumbnailJob(context)) return
        val id = GenerationAgentClient.activeJobId(context) ?: return
        adoptIfNeeded(context, id)
        val probeSnapshot = snapshotForPending(1)
        val nextFingerprint = LiveBatchPromptPolicy.fingerprint(probeSnapshot)
        val previous = synchronized(lock) { fingerprint }
        if (previous != null && previous == nextFingerprint) return
        val job = try {
            GenerationAgentClient.getJob(context, id)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "could not read job before refresh", error)
            return
        }
        if (job.isTerminal) {
            clear(id)
            return
        }
        if (!LiveBatchPromptPolicy.shouldSendRefresh(previous, nextFingerprint, job.pending)) return
        val snapshot = snapshotForPending(job.pending)
        val replacement = GeneratedImageTagBinding.buildPreparedImages(
            snapshot,
            chance = { Random.nextInt(100) },
            pickIndex = { size -> Random.nextInt(size) }
        )
        if (replacement.isEmpty()) return
        val requests = requestsFromPrepared(replacement)
        if (requests.isEmpty()) return
        try {
            GenerationAgentClient.refreshPending(context, id, requests)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "pending refresh failed", error)
            return
        }
        val spliced = synchronized(lock) {
            val next = LiveBatchPromptPolicy.splicePrepared(
                prepared,
                LiveBatchPromptPolicy.pendingStart(job.total, job.pending),
                replacement
            )
            jobId = id
            prepared = next
            fingerprint = nextFingerprint
            next
        }
        GenerationAgentClient.persistPrepared(context, spliced)
    }

    private fun adoptIfNeeded(context: Context, id: String) {
        synchronized(lock) {
            if (jobId == id && prepared.isNotEmpty()) return
            jobId = id
            if (prepared.isEmpty()) {
                prepared = GenerationAgentClient.persistedPrepared(context)
            }
        }
    }

    fun requestsFromPrepared(
        images: List<GeneratedImageTagBinding.PreparedImage>
    ): List<AgentGenerationRequest> = images.mapNotNull { prepared ->
        if (prepared.prompt.isBlank()) return@mapNotNull null
        AgentGenerationRequest(
            prompt = prepared.prompt,
            negativePrompt = prepared.negativePrompt,
            width = prepared.width.takeIf { it > 0 } ?: 720,
            height = prepared.height.takeIf { it > 0 } ?: 1280,
            steps = prepared.steps.takeIf { it > 0 } ?: 20,
            samplerName = prepared.sampler.ifBlank { "Euler a" },
            tags = TagManager.minimizeTags(prepared.tags.toSet()).toList(),
            cardStates = prepared.cardStates,
            randomPickedIds = prepared.randomPickedIds,
            randomEnabledCategories = prepared.randomEnabledCategories
        )
    }
}
