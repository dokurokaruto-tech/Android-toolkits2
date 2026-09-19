package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

// A filled button that doubles as a determinate progress bar.
class CivitaiProgressButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var fill: Float? = null
    private val fillPaint = Paint().apply {
        color = Color.WHITE
        alpha = 70
    }

    fun setFill(fraction: Float?) {
        fill = fraction?.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fraction = fill
        if (fraction != null && fraction > 0f) {
            canvas.drawRoundRect(
                0f, 0f, width * fraction, height.toFloat(),
                cornerRadius, cornerRadius, fillPaint
            )
        }
    }
}
