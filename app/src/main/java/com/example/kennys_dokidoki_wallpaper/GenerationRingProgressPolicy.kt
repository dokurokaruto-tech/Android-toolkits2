package com.example.kennys_dokidoki_wallpaper

/**
 * 青リングはいま描いている1枚、ピンクはバッチ全体。
 * 1枚だけのときは同じ速さになる。
 */
object GenerationRingProgressPolicy {
    const val INNER_COLOR = 0xFF00F0FF.toInt()
    const val OUTER_COLOR = 0xFFFF2D95.toInt()
    const val INNER_TRACK_COLOR = 0x3300F0FF
    const val OUTER_TRACK_COLOR = 0x33FF2D95
    const val STROKE_DP = 3f
    const val GAP_DP = 1.5f

    fun currentImage(
        rawCurrent: Float?,
        overall: Float,
        completed: Int,
        total: Int
    ): Float {
        if (rawCurrent != null) return rawCurrent.coerceIn(0f, 1f)
        val count = total.coerceAtLeast(1)
        return (overall.coerceIn(0f, 1f) * count - completed.coerceAtLeast(0)).coerceIn(0f, 1f)
    }

    fun overall(completed: Int, total: Int, currentImage: Float): Float {
        val count = total.coerceAtLeast(1)
        return ((completed.coerceAtLeast(0) + currentImage.coerceIn(0f, 1f)) / count).coerceIn(0f, 1f)
    }

    fun completedFromBatchIndex(currentBatch: Int): Int = (currentBatch - 1).coerceAtLeast(0)
}
