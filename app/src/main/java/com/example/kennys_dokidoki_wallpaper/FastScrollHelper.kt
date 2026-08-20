package com.example.kennys_dokidoki_wallpaper

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * 右端をなぞると一覧をジャンプする。
 * タップやケバブボタンは奪わず、縦にしっかりドラッグしたときだけ動かす。
 */
object FastScrollHelper {
    fun attach(rv: RecyclerView, edgeDp: Float = 22f) {
        val density = rv.resources.displayMetrics.density
        val edgePx = edgeDp * density
        val slop = ViewConfiguration.get(rv.context).scaledTouchSlop

        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var tracking = false
            private var dragging = false
            private var downX = 0f
            private var downY = 0f

            override fun onInterceptTouchEvent(recyclerView: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        dragging = false
                        tracking = e.x >= recyclerView.width - edgePx &&
                            !isOnInteractiveChild(recyclerView, e)
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!tracking) return false
                        val dy = abs(e.y - downY)
                        val dx = abs(e.x - downX)
                        if (dy > slop && dy > dx) {
                            dragging = true
                            jumpToPosition(recyclerView, e.y)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        dragging = false
                    }
                }
                return false
            }

            override fun onTouchEvent(recyclerView: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (dragging) jumpToPosition(recyclerView, e.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        tracking = false
                        dragging = false
                    }
                }
            }
        })
    }

    private fun jumpToPosition(rv: RecyclerView, touchY: Float) {
        val adapter = rv.adapter ?: return
        val count = adapter.itemCount
        if (count == 0) return
        val height = rv.height.coerceAtLeast(1)
        val percentage = (touchY / height).coerceIn(0f, 1f)
        val position = (percentage * (count - 1)).toInt()
        val layoutManager = rv.layoutManager
        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(position, 0)
        } else {
            rv.scrollToPosition(position)
        }
    }

    private fun isOnInteractiveChild(rv: RecyclerView, e: MotionEvent): Boolean {
        val child = rv.findChildViewUnder(e.x, e.y) ?: return false
        return hitsInteractive(child, e.rawX, e.rawY)
    }

    private fun hitsInteractive(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hitsInteractive(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        if (!view.isClickable && !view.isLongClickable) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + view.width &&
            rawY >= loc[1] && rawY <= loc[1] + view.height
    }
}
