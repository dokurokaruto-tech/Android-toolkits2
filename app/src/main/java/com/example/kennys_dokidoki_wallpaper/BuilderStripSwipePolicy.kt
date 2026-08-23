package com.example.kennys_dokidoki_wallpaper

import kotlin.math.abs

/**
 * 下部カード一覧のスワイプは、最初に決まった軸だけ動かす。
 */
object BuilderStripSwipePolicy {
    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    fun resolveAxis(dx: Float, dy: Float, touchSlop: Int, current: Axis): Axis {
        if (current != Axis.NONE) return current
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx <= touchSlop && ady <= touchSlop) return Axis.NONE
        return if (adx > ady) Axis.HORIZONTAL else Axis.VERTICAL
    }

    fun shouldIntercept(
        collapsed: Boolean,
        axis: Axis
    ): Boolean = collapsed || axis == Axis.VERTICAL

    fun shouldMoveVertically(axis: Axis): Boolean = axis == Axis.VERTICAL

    fun shouldScrollHorizontally(axis: Axis): Boolean = axis == Axis.HORIZONTAL
}
