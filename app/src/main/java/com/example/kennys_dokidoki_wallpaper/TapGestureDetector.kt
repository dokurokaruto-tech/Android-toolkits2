package com.example.kennys_dokidoki_wallpaper

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * 連続タップ / タップ+長押し / 単独長押し を判定する。
 *
 *   DOWN ─┬─ 移動が大きい ──▶ 全キャンセル
 *         ├─ tapCount==0 で 1000ms 静止 ──▶ onHold1s()
 *         ├─ tapCount>=1 で 400ms 静止  ──▶ onTapsAndHold(tapCount+1)
 *         └─ UP (350ms 以内に次の DOWN が無い) ──▶ onTaps(tapCount)
 *
 * 旧コードは MyWallpaperService と ChatOverlayActivity に同じ 80 行が
 * コピーされていて、片方だけ修正される事故が起きていた。
 */
class TapGestureDetector(
    private val callbacks: Callbacks,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    interface Callbacks {
        fun onTaps(count: Int)
        fun onTapsAndHold(count: Int)
        fun onHold1s()
        /** 指が触れた瞬間 (HUD 表示などに使う) */
        fun onTouchStart() {}
    }

    private companion object {
        const val MULTI_TAP_TIMEOUT_MS = 350L
        const val TAP_HOLD_TIMEOUT_MS = 400L
        const val LONG_PRESS_TIMEOUT_MS = 1000L
        const val TOUCH_SLOP_PX = 50f
    }

    private var tapCount = 0
    private var lastTapUpTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var holdFired = false

    private val commitTaps = Runnable {
        val count = tapCount
        tapCount = 0
        if (count > 0) {
            callbacks.onTaps(count)
        }
    }
    private val fireTapHold = Runnable {
        holdFired = true
        val count = tapCount + 1
        tapCount = 0
        callbacks.onTapsAndHold(count)
    }
    private val fireLongPress = Runnable {
        holdFired = true
        tapCount = 0
        callbacks.onHold1s()
    }

    /** true を返しても消費扱いにはしない。呼び出し側は super へ流してよい。 */
    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> cancelPending()
        }
    }

    fun reset() {
        handler.removeCallbacks(commitTaps)
        cancelPending()
        tapCount = 0
    }

    private fun onDown(event: MotionEvent) {
        callbacks.onTouchStart()
        handler.removeCallbacks(commitTaps)

        val now = event.eventTime
        if (tapCount > 0 && now - lastTapUpTime > MULTI_TAP_TIMEOUT_MS) {
            tapCount = 0
        }

        downX = event.x
        downY = event.y
        holdFired = false

        if (tapCount == 0) {
            handler.postDelayed(fireLongPress, LONG_PRESS_TIMEOUT_MS)
        } else {
            handler.postDelayed(fireTapHold, TAP_HOLD_TIMEOUT_MS)
        }
    }

    private fun onMove(event: MotionEvent) {
        if (hypot(event.x - downX, event.y - downY) > TOUCH_SLOP_PX) {
            cancelPending()
        }
    }

    private fun onUp(event: MotionEvent) {
        cancelPending()
        if (holdFired) {
            return
        }
        if (hypot(event.x - downX, event.y - downY) > TOUCH_SLOP_PX) {
            tapCount = 0
            return
        }

        tapCount++
        lastTapUpTime = event.eventTime
        handler.postDelayed(commitTaps, MULTI_TAP_TIMEOUT_MS)
    }

    private fun cancelPending() {
        handler.removeCallbacks(fireTapHold)
        handler.removeCallbacks(fireLongPress)
    }
}
