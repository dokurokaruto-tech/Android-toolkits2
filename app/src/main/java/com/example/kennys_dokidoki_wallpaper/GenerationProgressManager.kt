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
        val totalBatch: Int = 0
    )

    private val _state = MutableStateFlow(ProgressState())
    val state = _state.asStateFlow()

    // 割り込みフラグ
    var shouldInterrupt: Boolean = false
    var shouldStopGracefully: Boolean = false
    var shouldSkip: Boolean = false
    var isBatch: Boolean = false

    fun updateState(isGenerating: Boolean, progress: Float, currentImage: Bitmap?, statusText: String = "") {
        _state.value = _state.value.copy(
            isGenerating = isGenerating,
            progress = progress,
            currentImage = currentImage,
            statusText = statusText
        )
    }

    fun updateBatchProgress(current: Int, total: Int) {
        _state.value = _state.value.copy(
            currentBatch = current,
            totalBatch = total
        )
    }

    fun startGeneration(batchMode: Boolean = false, total: Int = 1) {
        isBatch = batchMode
        shouldInterrupt = false
        shouldStopGracefully = false
        shouldSkip = false
        _state.value = ProgressState(
            isGenerating = true,
            statusText = "錬成準備中...",
            currentBatch = if (batchMode) 1 else 0,
            totalBatch = if (batchMode) total else 0
        )
    }

    fun endGeneration(force: Boolean = false) {
        if (force || !isBatch) {
            _state.value = _state.value.copy(isGenerating = false)
        }
    }
}
