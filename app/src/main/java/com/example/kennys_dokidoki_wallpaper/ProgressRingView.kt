package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 中止ボタン（生成中のボタン）の周りに、現在生成中の1枚の進捗を
 * 円弧で描くオーバーレイ。
 *
 * - ボタンそのもののデザインは変えず、上から重ねて配置する前提。
 * - 上(12時)から時計回りに明るい線が進み、進捗100%で一周する。
 * - 進捗0%では薄いトラック(背景円)のみ。
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f // 0.0 .. 1.0

    private val density = resources.displayMetrics.density

    /** 背景の薄いトラック円 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0x33FFFFFF
    }

    /** 進捗を示す明るい円弧 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0xFF00F0FF.toInt() // 明るいシアン
        strokeCap = Paint.Cap.ROUND
    }

    fun setProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sw = progressPaint.strokeWidth
        val inset = sw / 2f
        val rect = RectF(inset, inset, width - inset, height - inset)
        // 薄いトラック（背景の円）
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)
        // 進捗円弧：上(12時)から時計回り
        if (progress > 0f) {
            canvas.drawArc(rect, -90f, 360f * progress, false, progressPaint)
        }
    }
}
