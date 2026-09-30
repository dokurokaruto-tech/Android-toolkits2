package com.example.kennys_dokidoki_wallpaper

/**
 * 日記の用紙に写真を置くときの計算。ドラッグ量とピンチ倍率を
 * 用紙内に収まる比率へ丸める。View に触らないので単体テストできる。
 */
object DiaryLayoutPolicy {

    const val MIN_WIDTH_RATIO = 0.12f
    const val MAX_WIDTH_RATIO = 1.0f
    const val DEFAULT_WIDTH_RATIO = 0.45f

    /** ピクセルと比率の変換。用紙の幅（高さ）を基準にする */
    fun toRatio(px: Float, canvasSize: Int): Float =
        if (canvasSize <= 0) 0f else px / canvasSize

    fun toPx(ratio: Float, canvasSize: Int): Int = (ratio * canvasSize).toInt()

    /** 写真が用紙からはみ出さない位置へ丸める */
    fun clampPosition(ratio: Float, sizeRatio: Float): Float {
        val max = (1f - sizeRatio).coerceAtLeast(0f)
        return ratio.coerceIn(0f, max)
    }

    fun resize(currentWidthRatio: Float, factor: Float): Float =
        (currentWidthRatio * factor).coerceIn(MIN_WIDTH_RATIO, MAX_WIDTH_RATIO)
}
