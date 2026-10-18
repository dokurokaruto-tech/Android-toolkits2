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
 * 監視はアプリを開いている時だけ。背面へ行くとポーリングごと静止する。
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
    private var sleeping = true

    @Synchronized
    fun bind(context: Context, date: String?) {
        appContext = context.applicationContext
        watchedDate = date?.takeIf { it.isNotBlank() } ?: watchedDate
        subscribers += 1
        if (pollJob?.isActive == true) {
            scope.launch { refreshOnce() }
            return
        }
        if (!sleeping) {
            startPolling()
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

    /** アプリが前面へ戻った。購読が残っている時だけ監視を再開する。 */
    @Synchronized
    fun onAppWaking() {
        sleeping = false
        if (subscribers > 0 && pollJob?.isActive != true) {
            startPolling()
        }
    }

    /** アプリが背面へ行った。購読は残したままポーリングを止める。 */
    @Synchronized
    fun onAppSleeping() {
        sleeping = true
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling() {
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

    private suspend fun refreshOnce() {
        val context = appContext ?: return
        val date = watchedDate
        try {
            val folders = GenerationAgentClient.fetchFolders(context)
            val images = if (date.isNullOrBlank()) {
                emptyList()
            } else {
                GenerationAgentClient.fetchImagePage(context, date).images
            }
            GeneratedImageDraftStore.seedPage(context, images)
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
