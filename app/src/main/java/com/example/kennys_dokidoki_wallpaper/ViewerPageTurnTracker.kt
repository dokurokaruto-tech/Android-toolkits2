package com.example.kennys_dokidoki_wallpaper

import android.view.MotionEvent
import android.view.View

/**
 * 全画面の左右タップだけをページ送りにする。指が slop を超えて動いたらスワイプとみなし無視する。
 */
class ViewerPageTurnTracker(
    private val slopPx: Float,
    private val onTap: (goLeft: Boolean) -> Unit
) {
    private var downX = 0f
    private var downY = 0f
    private var downInLeft = false

    fun listenerForLeftZone(): View.OnTouchListener = listener(fixedLeft = true)

    fun listenerForRightZone(): View.OnTouchListener = listener(fixedLeft = false)

    fun listenerForFullWidth(): View.OnTouchListener = listener(fixedLeft = null)

    private fun listener(fixedLeft: Boolean?): View.OnTouchListener {
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downInLeft = fixedLeft ?: ViewerTapPolicy.isLeftHalf(event.x, view.width.toFloat())
                }
                MotionEvent.ACTION_UP -> {
                    if (ViewerTapPolicy.isTap(event.rawX - downX, event.rawY - downY, slopPx)) {
                        onTap(downInLeft)
                    }
                }
            }
            true
        }
    }
}
