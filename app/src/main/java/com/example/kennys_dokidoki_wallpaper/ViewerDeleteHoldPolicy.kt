package com.example.kennys_dokidoki_wallpaper

/**
 * 全画面の削除は警告文を出さず、アイコンを1秒押し続けたときだけ確定する。
 * 円は真上から時計回りに一周する。
 */
object ViewerDeleteHoldPolicy {
    const val HOLD_MS = 1000L
    const val START_ANGLE_DEGREES = -90f

    fun progress(elapsedMs: Long): Float =
        (elapsedMs.toFloat() / HOLD_MS.toFloat()).coerceIn(0f, 1f)

    fun isConfirmed(elapsedMs: Long): Boolean = elapsedMs >= HOLD_MS

    fun sweepDegrees(progress: Float): Float = 360f * progress.coerceIn(0f, 1f)

    fun movedBeyondSlop(downX: Float, downY: Float, x: Float, y: Float, slopPx: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slopPx * slopPx
    }
}
