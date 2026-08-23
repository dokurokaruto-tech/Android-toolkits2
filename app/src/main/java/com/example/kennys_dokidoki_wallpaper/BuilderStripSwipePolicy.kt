package com.example.kennys_dokidoki_wallpaper

import kotlin.math.abs

/**
 * 下部カード一覧のスワイプは、ハンドルとカード本体だけ受け、
 * 最初に決まった軸だけ動かす。余白は裏のビルダーへ通す。
 */
object BuilderStripSwipePolicy {
    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    enum class Hit { NONE, HANDLE, CARD, HISTORY }

    fun resolveAxis(dx: Float, dy: Float, touchSlop: Int, current: Axis): Axis {
        if (current != Axis.NONE) return current
        val adx = abs(dx)
        val ady = abs(dy)
        if (adx <= touchSlop && ady <= touchSlop) return Axis.NONE
        return if (adx > ady) Axis.HORIZONTAL else Axis.VERTICAL
    }

    fun contains(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun hit(onHistory: Boolean, onHandle: Boolean, onCard: Boolean): Hit = when {
        onHistory -> Hit.HISTORY
        onHandle -> Hit.HANDLE
        onCard -> Hit.CARD
        else -> Hit.NONE
    }

    fun acceptsGesture(hit: Hit): Boolean = hit != Hit.NONE

    fun acceptsSwipe(hit: Hit): Boolean = hit == Hit.HANDLE || hit == Hit.CARD

    fun shouldIntercept(
        collapsed: Boolean,
        axis: Axis,
        hit: Hit = Hit.CARD
    ): Boolean {
        if (!acceptsSwipe(hit)) return false
        return collapsed || axis == Axis.VERTICAL
    }

    fun shouldMoveVertically(axis: Axis): Boolean = axis == Axis.VERTICAL

    fun shouldScrollHorizontally(axis: Axis): Boolean = axis == Axis.HORIZONTAL
}
