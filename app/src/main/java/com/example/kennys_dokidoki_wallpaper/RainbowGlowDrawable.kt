package com.example.kennys_dokidoki_wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 入力枠の外周を流れる虹色の光。
 *
 *   ┌──────────────────────┐
 *   │  ░▒▓ 虹グラデ枠 ▓▒░  │  ← phase を進めると色が横に流れる
 *   └──────────────────────┘
 *
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
    private val rect = RectF()
    private var currentAlpha = 255

    /** 0..1。グラデーションの流れる位置。 */
    var phase: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = strokeWidthPx / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)

        // 幅2倍のグラデを phase 分だけ横にずらし、途切れなく流れて見せる
        val width = rect.width().coerceAtLeast(1f)
        val offset = -phase * width
        paint.shader = LinearGradient(
            offset, 0f, offset + width * 2f, 0f,
            colors + colors, null, Shader.TileMode.REPEAT
        )
        paint.alpha = currentAlpha
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }

    override fun setAlpha(alpha: Int) {
        currentAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = currentAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
