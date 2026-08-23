package com.example.kennys_dokidoki_wallpaper

import kotlin.math.hypot

/**
 * 全画面閲覧で、指が動いた量から「タップ」と「スワイプ」を分ける。
 * スワイプなら前後の画像へ進まない。
 */
object ViewerTapPolicy {
    fun distance(deltaX: Float, deltaY: Float): Float =
        hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

    fun isTap(deltaX: Float, deltaY: Float, slopPx: Float): Boolean =
        isTapAfterTravel(distance(deltaX, deltaY), slopPx)

    /**
     * 始点と終点が近くても、途中で slop を超えて動いていればスワイプ。
     */
    fun isTapAfterTravel(maxTravelPx: Float, slopPx: Float): Boolean {
        if (slopPx <= 0f) return maxTravelPx <= 0f
        return maxTravelPx <= slopPx
    }

    fun isLeftHalf(x: Float, width: Float): Boolean {
        if (width <= 0f) return true
        return x < width / 2f
    }
}
