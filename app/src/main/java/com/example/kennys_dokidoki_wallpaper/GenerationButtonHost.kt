package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 生成ボタンの実寸だけをレイアウトに使うホスト。
 * 進捗リングはボタンより一回り大きいが、計測対象から外すので
 * ボタン位置がずれたり親が膨らんだりしない。
 */
class GenerationButtonHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxW = 0
        var maxH = 0
        var childState = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child is ProgressRingView) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as LayoutParams
            maxW = maxOf(maxW, child.measuredWidth + lp.leftMargin + lp.rightMargin)
            maxH = maxOf(maxH, child.measuredHeight + lp.topMargin + lp.bottomMargin)
            childState = combineMeasuredStates(childState, child.measuredState)
        }
        maxW += paddingLeft + paddingRight
        maxH += paddingTop + paddingBottom
        maxW = maxOf(maxW, suggestedMinimumWidth)
        maxH = maxOf(maxH, suggestedMinimumHeight)
        setMeasuredDimension(
            resolveSizeAndState(maxW, widthMeasureSpec, childState),
            resolveSizeAndState(
                maxH,
                heightMeasureSpec,
                childState shl MEASURED_HEIGHT_STATE_SHIFT
            )
        )
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child is ProgressRingView && child.visibility != GONE) {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
    }
}
