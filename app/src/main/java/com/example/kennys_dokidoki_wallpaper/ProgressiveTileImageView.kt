package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Draws independently downloaded lossless image strips at their real vertical positions.
 * Strips are first stamped into one source-sized bitmap so scaled drawing never shows
 * the black seam that bilinear filtering leaves between separate tiles.
 */
class ProgressiveTileImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blitPaint = Paint()
    private var composed: Bitmap? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var tileMode = false

    fun prepareForLoad() {
        clearTiles()
        tileMode = false
        setImageDrawable(null)
        invalidate()
    }

    fun beginProgressiveLoad(width: Int, height: Int) {
        clearTiles()
        sourceWidth = width
        sourceHeight = height
        tileMode = width > 0 && height > 0
        if (tileMode) {
            composed = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.TRANSPARENT)
                }
            } catch (_: OutOfMemoryError) {
                tileMode = false
                null
            }
        }
        setImageDrawable(null)
        invalidate()
    }

    fun appendDecodedTile(bitmap: Bitmap, top: Int) {
        val canvasBitmap = composed
        if (!tileMode || canvasBitmap == null || canvasBitmap.isRecycled) {
            bitmap.recycle()
            return
        }
        Canvas(canvasBitmap).drawBitmap(bitmap, 0f, top.toFloat(), blitPaint)
        if (!bitmap.isRecycled) bitmap.recycle()
        invalidate()
    }

    fun showCompleteDrawable(drawable: Drawable?) {
        clearTiles()
        tileMode = false
        setImageDrawable(drawable)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = composed
        if (!tileMode || bitmap == null || bitmap.isRecycled || sourceWidth <= 0 || sourceHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val left = (width - drawnWidth) / 2f
        val topOffset = (height - drawnHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, topOffset, left + drawnWidth, topOffset + drawnHeight),
            paint
        )
    }

    private fun clearTiles() {
        composed?.let { if (!it.isRecycled) it.recycle() }
        composed = null
        sourceWidth = 0
        sourceHeight = 0
    }

    override fun onDetachedFromWindow() {
        clearTiles()
        super.onDetachedFromWindow()
    }
}
