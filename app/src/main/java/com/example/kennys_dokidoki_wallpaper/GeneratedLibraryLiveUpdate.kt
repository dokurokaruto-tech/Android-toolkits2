package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 閲覧中にPCで画像が完成したら、フォルダ一覧と日付フォルダの中身をすぐ取り直す。
 * Activity 寿命ではなくプロセス寿命で回す。
 */
object GeneratedLibraryLiveUpdate {
    data class Snapshot(
        val date: String?,
        val folders: List<AgentGeneratedFolder> = emptyList(),
        val images: List<AgentGeneratedImage> = emptyList(),
        val revision: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshot = MutableStateFlow(Snapshot(date = null))
    val snapshot = _snapshot.asStateFlow()

    private var subscribers = 0
    private var watchedDate: String? = null
    private var appContext: Context? = null
    private var pollJob: Job? = null

    @Synchronized
    fun bind(context: Context, date: String?) {
        appContext = context.applicationContext
        watchedDate = date?.takeIf { it.isNotBlank() } ?: watchedDate
        subscribers += 1
        if (pollJob?.isActive == true) {
            scope.launch { refreshOnce() }
            return
        }
        pollJob = scope.launch {
            refreshOnce()
            var lastGenerating = GenerationProgressManager.state.value.isGenerating
            var lastBatch = GenerationProgressManager.state.value.currentBatch
            while (isActive && subscribers > 0) {
                val state = GenerationProgressManager.state.value
                val finished = lastGenerating && !state.isGenerating
                val progressed = state.currentBatch != lastBatch
                if (state.isGenerating || finished || progressed) {
                    refreshOnce()
                }
                lastGenerating = state.isGenerating
                lastBatch = state.currentBatch
                delay(if (state.isGenerating) 2_000L else 8_000L)
            }
        }
    }

    @Synchronized
    fun unbind() {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        if (subscribers == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private suspend fun refreshOnce() {
        val context = appContext ?: return
        val date = watchedDate
        try {
            val folders = GenerationAgentClient.fetchFolders(context)
            val images = if (date.isNullOrBlank()) {
                emptyList()
            } else {
                GenerationAgentClient.fetchImages(context, date)
            }
            images.forEach { image ->
                GeneratedImageDraftStore.seedGeneratedSource(
                    context,
                    Uri.parse(image.url),
                    image.tags,
                    image.cardStates,
                    image.width,
                    image.height,
                    image.steps,
                    image.sampler,
                    image.prompt,
                    image.randomPickedIds,
                    image.randomEnabledCategories,
                    image.seed,
                    image.negativePrompt
                )
            }
            _snapshot.value = Snapshot(
                date = date,
                folders = folders,
                images = images,
                revision = System.currentTimeMillis()
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
    }
}
