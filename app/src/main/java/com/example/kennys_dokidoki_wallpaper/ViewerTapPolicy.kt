package com.example.kennys_dokidoki_wallpaper

import kotlin.math.hypot

/**
 * 全画面閲覧で、指が動いた量から「タップ」と「スワイプ」を分ける。
 * スワイプなら前後の画像へ進まない。
 */
object ViewerTapPolicy {
    fun isTap(deltaX: Float, deltaY: Float, slopPx: Float): Boolean {
        if (slopPx <= 0f) return deltaX == 0f && deltaY == 0f
        return hypot(deltaX.toDouble(), deltaY.toDouble()) <= slopPx
    }

    fun isLeftHalf(x: Float, width: Float): Boolean {
        if (width <= 0f) return true
        return x < width / 2f
    }
}
