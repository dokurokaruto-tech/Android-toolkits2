package com.example.kennys_dokidoki_wallpaper

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 錬成（画像生成）の進捗状態を管理するシングルトンよ！
 * PiP画面とStabilityManagerの間で情報をやり取りする司令塔ね♪
 */
object GenerationProgressManager {
    
    data class ProgressState(
        val isGenerating: Boolean = false,
        val progress: Float = 0f, // 0.0 ~ 1.0
        val currentImage: Bitmap? = null,
        val statusText: String = "",
        val currentBatch: Int = 0,
        val totalBatch: Int = 0,
        val currentImageProgress: Float = 0f,
        val completedCount: Int = 0,
        // サムネイル生成など PiP を伴わない（静かな）生成。ビルダーのPiP復活ボタン等を出さない。
        val silent: Boolean = false
    )

    private val _state = MutableStateFlow(ProgressState())
    val state = _state.asStateFlow()

    // 割り込みフラグ
    var shouldInterrupt: Boolean = false
    var shouldStopGracefully: Boolean = false
    var shouldSkip: Boolean = false
    var isBatch: Boolean = false

    // 直近のエラー（コード + 詳細）。コードは原因を一意に特定する。
    // E01..E10 の仕様は StabilityManager の定数を参照。
    var lastErrorCode: String? = null
        private set
    var lastErrorMessage: String? = null
        private set

    fun reportError(code: String, message: String) {
        lastErrorCode = code
        lastErrorMessage = message
    }

    fun clearError() {
        lastErrorCode = null
        lastErrorMessage = null
    }

    fun updateState(
        isGenerating: Boolean,
        progress: Float,
        currentImage: Bitmap?,
        statusText: String = "",
        currentImageProgress: Float? = null,
        completedCount: Int? = null
    ) {
        val current = _state.value
        _state.value = current.copy(
            isGenerating = isGenerating,
            progress = progress,
            currentImage = currentImage,
            statusText = statusText,
            currentImageProgress = currentImageProgress ?: current.currentImageProgress,
            completedCount = completedCount ?: current.completedCount
        )
    }

    fun updateBatchProgress(current: Int, total: Int, completedCount: Int? = null) {
        _state.value = _state.value.copy(
            currentBatch = current,
            totalBatch = total,
            completedCount = completedCount ?: GenerationRingProgressPolicy.completedFromBatchIndex(current)
        )
    }

    fun startGeneration(batchMode: Boolean = false, total: Int = 1, silent: Boolean = false) {
        isBatch = batchMode
        shouldInterrupt = false
        shouldStopGracefully = false
        shouldSkip = false
        clearError()
        _state.value = ProgressState(
            isGenerating = true,
            statusText = "錬成準備中...",
            currentBatch = if (batchMode) 1 else 0,
            totalBatch = if (batchMode) total else 0,
            currentImageProgress = 0f,
            completedCount = 0,
            silent = silent
        )
    }

    fun endGeneration(force: Boolean = false) {
        if (force || !isBatch) {
            _state.value = _state.value.copy(isGenerating = false, silent = false)
        }
    }
}
