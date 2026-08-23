package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 中断ボタン（生成中のボタン）の形に沿った 角丸四角形 の進捗バーオーバーレイ。
 *
 * - ボタンそのもののデザインは変えず、上から重ねて配置する前提。
 * - ボタンの角丸四角形の外形に沿って、**下中央から反時計回り**に明るい線が進み、
 *   進捗100%で一周する。
 * - 進捗0%では薄いトラック（背景の角丸四角形）のみ。
 *
 * 反時計回りの定義（この実装）：
 *   下中央 → 右へ（底辺）→ 右上へ → 左へ（上辺）→ 左下へ → 下中央へ戻る
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var progress = 0f // 0.0 .. 1.0
    private var cornerRadius = 0f // px

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    private val density = resources.displayMetrics.density

    /** 背景の薄いトラック */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0x33FFFFFF
    }

    /** 進捗を示す明るい線 */
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = 0xFF00F0FF.toInt() // 明るいシアン
        strokeCap = Paint.Cap.ROUND
    }

    private val outlinePath = Path()
    private val segmentPath = Path()
    private val pathMeasure = PathMeasure()

    fun setProgress(p: Float) {
        val clamped = p.coerceIn(0f, 1f)
        if (clamped != progress) {
            progress = clamped
            invalidate()
        }
    }

    fun setCornerRadius(radiusPx: Float) {
        if (radiusPx != cornerRadius) {
            cornerRadius = radiusPx
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sw = progressPaint.strokeWidth
        val inset = sw / 2f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= inset * 2f || h <= inset * 2f) return
        val rect = RectF(inset, inset, w - inset, h - inset)
        val radius = cornerRadius.coerceIn(0f, minOf(rect.width(), rect.height()) / 2f)

        outlinePath.reset()
        buildRoundedRectOutline(outlinePath, rect, radius)

        // 背景トラック（全体）
        canvas.drawPath(outlinePath, trackPaint)

        // 進捗：パス先頭(下中央)から 長さ=周長×progress だけ切り出す（反時計回りに進む）
        if (progress > 0f) {
            pathMeasure.setPath(outlinePath, false)
            val total = pathMeasure.length
            if (total > 0f) {
                val progLen = (total * progress).coerceIn(0f, total)
                segmentPath.reset()
                pathMeasure.getSegment(0f, progLen, segmentPath, true)
                canvas.drawPath(segmentPath, progressPaint)
            }
        }
    }

    /**
     * rect の角丸四角形を「下中央」から「反時計回り」にたどるパスを構築する。
     * 角の円弧はすべて負のsweep(-90°)=画面上の反時計回りで描く。
     */
    private fun buildRoundedRectOutline(path: Path, rect: RectF, radius: Float) {
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val cx = (l + r) / 2f

        path.moveTo(cx, b)
        path.lineTo(r - radius, b)
        path.arcTo(RectF(r - 2f * radius, b - 2f * radius, r, b), 90f, -90f, false)
        path.lineTo(r, t + radius)
        path.arcTo(RectF(r - 2f * radius, t, r, t + 2f * radius), 0f, -90f, false)
        path.lineTo(l + radius, t)
        path.arcTo(RectF(l, t, l + 2f * radius, t + 2f * radius), 270f, -90f, false)
        path.lineTo(l, b - radius)
        path.arcTo(RectF(l, b - 2f * radius, l + 2f * radius, b), 180f, -90f, false)
        path.lineTo(cx, b)
        path.close()
    }
}
