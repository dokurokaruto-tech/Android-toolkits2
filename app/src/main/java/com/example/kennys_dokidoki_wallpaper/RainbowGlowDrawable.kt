package com.example.kennys_dokidoki_wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 入力枠の外周を流れる虹色の光。初回セリフ生成時は星形のきらめきも重ねる。
 * alpha は外側から setAlpha で制御し、フェードイン/アウトに使う。
 */
internal class RainbowGlowDrawable(
    private val cornerRadiusPx: Float,
    private val strokeWidthPx: Float
) : Drawable() {
    private val colors = intArrayOf(
        Color.parseColor("#FF5252"), Color.parseColor("#FFB300"), Color.parseColor("#FFEB3B"),
        Color.parseColor("#69F0AE"), Color.parseColor("#40C4FF"), Color.parseColor("#B388FF"),
        Color.parseColor("#FF4081"), Color.parseColor("#FF5252")
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        setShadowLayer(strokeWidthPx * 2f, 0f, 0f, Color.WHITE)
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sparklePath = Path()
    private val rect = RectF()
    private var currentAlpha = 255

    /** 0..1。グラデーションときらめきが流れる位置。 */
    var phase: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    var sparklesEnabled: Boolean = false
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = strokeWidthPx / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)

        val width = rect.width().coerceAtLeast(1f)
        val offset = -phase * width
        paint.shader = LinearGradient(
            offset, 0f, offset + width * 2f, 0f,
            colors + colors, null, Shader.TileMode.REPEAT
        )
        paint.alpha = currentAlpha
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

        if (sparklesEnabled && currentAlpha > 0) {
            drawSparkles(canvas)
        }
    }

    private fun drawSparkles(canvas: Canvas) {
        val sparkleInset = strokeWidthPx * 2f
        val points = arrayOf(
            floatArrayOf(rect.left + rect.width() * 0.13f, rect.top + sparkleInset),
            floatArrayOf(rect.left + rect.width() * 0.43f, rect.top + sparkleInset),
            floatArrayOf(rect.right - rect.width() * 0.12f, rect.top + sparkleInset),
            floatArrayOf(rect.right - sparkleInset, rect.top + rect.height() * 0.48f),
            floatArrayOf(rect.left + rect.width() * 0.70f, rect.bottom - sparkleInset),
            floatArrayOf(rect.left + rect.width() * 0.24f, rect.bottom - sparkleInset),
            floatArrayOf(rect.left + sparkleInset, rect.top + rect.height() * 0.62f)
        )
        val sparkleColors = intArrayOf(
            Color.WHITE,
            Color.parseColor("#FFE082"),
            Color.parseColor("#E1BEE7"),
            Color.WHITE,
            Color.parseColor("#FFF59D"),
            Color.parseColor("#D1C4E9"),
            Color.WHITE
        )
        val phaseRadians = phase * (2f * PI.toFloat())

        points.forEachIndexed { index, point ->
            val pulse = (sin(phaseRadians + index * 2.1f) + 1f) * 0.5f
            val radius = strokeWidthPx * (0.8f + pulse * 1.1f)
            val x = point[0]
            val y = point[1]
            sparklePaint.color = sparkleColors[index]
            sparklePaint.alpha = (currentAlpha * (0.18f + pulse * 0.82f)).toInt().coerceIn(0, 255)
            sparklePaint.setShadowLayer(strokeWidthPx * (0.8f + pulse), 0f, 0f, sparkleColors[index])

            sparklePath.reset()
            for (vertex in 0 until 8) {
                val angle = ((vertex * 45f - 90f + phase * 180f + index * 17f) * PI / 180.0).toFloat()
                val vertexRadius = if (vertex % 2 == 0) radius else radius * 0.2f
                val vertexX = x + cos(angle) * vertexRadius
                val vertexY = y + sin(angle) * vertexRadius
                if (vertex == 0) sparklePath.moveTo(vertexX, vertexY) else sparklePath.lineTo(vertexX, vertexY)
            }
            sparklePath.close()
            canvas.drawPath(sparklePath, sparklePaint)
            canvas.drawCircle(x, y, radius * 0.12f, sparklePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        currentAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = currentAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        sparklePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
