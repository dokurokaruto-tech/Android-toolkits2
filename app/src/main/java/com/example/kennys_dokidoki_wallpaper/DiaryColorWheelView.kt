package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** 円形の色相・鮮やかさホイール。明るさ用スライダーは設けず、円盤上の位置で色を選ぶ。 */
internal class DiaryColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wheelBitmap: Bitmap? = null
    private var centerX = 0f
    private var centerY = 0f
    private var wheelRadius = 0f
    private var selectedHue = 0f
    private var selectedSaturation = 0f
    private var selectedColor = Color.WHITE
    private var trackingTouch = false
    private var colorChangedListener: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "円形カラーピッカー"
    }

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        selectedHue = hsv[0]
        selectedSaturation = hsv[1]
        selectedColor = color
        updateContentDescription()
        invalidate()
    }

    fun setOnColorChangedListener(listener: ((Int) -> Unit)?) {
        colorChangedListener = listener
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val size = minOf(width, height)
        if (size <= 0) return
        wheelBitmap?.recycle()
        wheelBitmap = createWheelBitmap(size)
        centerX = width / 2f
        centerY = height / 2f
        wheelRadius = size / 2f - dp(2f)
        invalidate()
    }

    private fun createWheelBitmap(size: Int): Bitmap {
        val pixels = IntArray(size * size)
        val center = (size - 1) / 2f
        val radius = (size - 1) / 2f - dp(2f).coerceAtMost(size / 8f)
        val radiusSquared = radius * radius

        for (y in 0 until size) {
            val dy = y - center
            for (x in 0 until size) {
                val dx = x - center
                val distanceSquared = dx * dx + dy * dy
                pixels[y * size + x] = if (distanceSquared <= radiusSquared) {
                    var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (hue < 0f) hue += 360f
                    val saturation = (kotlin.math.sqrt(distanceSquared) / radius).coerceIn(0f, 1f)
                    hsvColor(hue, saturation)
                } else {
                    Color.TRANSPARENT
                }
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** HSV value is fixed at 1; angle chooses hue and distance from the center chooses saturation. */
    private fun hsvColor(hue: Float, saturation: Float): Int {
        val chroma = saturation.coerceIn(0f, 1f)
        val hueSector = (hue.coerceIn(0f, 360f) / 60f).coerceAtMost(5.999f)
        val sector = hueSector.toInt()
        val second = chroma * (1f - abs(hueSector % 2f - 1f))
        var red = 0f
        var green = 0f
        var blue = 0f
        when (sector) {
            0 -> { red = chroma; green = second }
            1 -> { red = second; green = chroma }
            2 -> { green = chroma; blue = second }
            3 -> { green = second; blue = chroma }
            4 -> { red = second; blue = chroma }
            else -> { red = chroma; blue = second }
        }
        val match = 1f - chroma
        return Color.rgb(
            ((red + match) * 255f).roundToInt(),
            ((green + match) * 255f).roundToInt(),
            ((blue + match) * 255f).roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = wheelBitmap ?: return
        val side = minOf(width, height).toFloat()
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + side, top + side), bitmapPaint)

        markerPaint.style = Paint.Style.STROKE
        markerPaint.strokeWidth = dp(4f)
        markerPaint.color = Color.BLACK
        val radians = selectedHue * PI / 180.0
        val markerX = centerX + cos(radians).toFloat() * selectedSaturation * wheelRadius
        val markerY = centerY + sin(radians).toFloat() * selectedSaturation * wheelRadius
        canvas.drawCircle(markerX, markerY, dp(8f), markerPaint)
        markerPaint.strokeWidth = dp(2f)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(markerX, markerY, dp(8f), markerPaint)
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = selectedColor
        canvas.drawCircle(markerX, markerY, dp(4f), markerPaint)
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val distance = hypot((event.x - centerX).toDouble(), (event.y - centerY).toDouble()).toFloat()
                if (distance > wheelRadius + dp(16f)) return false
                trackingTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
                selectAt(event.x, event.y)
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!trackingTouch) return false
                selectAt(event.x, event.y)
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (!trackingTouch) return false
                selectAt(event.x, event.y)
                trackingTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                trackingTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun selectAt(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        selectedHue = (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
        selectedSaturation = (distance / wheelRadius).coerceIn(0f, 1f)
        selectedColor = Color.HSVToColor(floatArrayOf(selectedHue, selectedSaturation, 1f))
        updateContentDescription()
        invalidate()
        colorChangedListener?.invoke(selectedColor)
    }

    private fun updateContentDescription() {
        contentDescription = String.format(
            java.util.Locale.US,
            "円形カラーピッカー、選択中 #%06X",
            selectedColor and 0xFFFFFF
        )
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
