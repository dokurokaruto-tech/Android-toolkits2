package com.example.kennys_dokidoki_wallpaper

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/** Reveals a newly loaded original image progressively from top to bottom. */
class TopDownRevealImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private var revealProgress = 1f
    private var animator: ValueAnimator? = null

    fun prepareForLoad() {
        animator?.cancel()
        revealProgress = 0f
        setImageDrawable(null)
        invalidate()
    }

    fun startTopDownReveal(durationMs: Long = 850L) {
        animator?.cancel()
        revealProgress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height * revealProgress)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
