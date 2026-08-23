package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** アイコンの周りに、真上から時計回りへ進む円の進捗を重ねる。 */
class HoldConfirmRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f
    private val density = resources.displayMetrics.density
    private val bounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0x66FFFFFF
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = 0xFFFF3366.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (next != progress) {
            progress = next
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = progressPaint.strokeWidth / 2f + density
        if (width <= inset * 2f || height <= inset * 2f) return
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, trackPaint)
        val sweep = ViewerDeleteHoldPolicy.sweepDegrees(progress)
        if (sweep > 0f) {
            canvas.drawArc(
                bounds,
                ViewerDeleteHoldPolicy.START_ANGLE_DEGREES,
                sweep,
                false,
                progressPaint
            )
        }
    }
}
