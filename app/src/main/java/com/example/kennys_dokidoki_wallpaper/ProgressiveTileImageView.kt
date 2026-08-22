package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * Draws independently downloaded lossless image strips at their real vertical positions.
 * This is network-progress rendering, not a reveal/fade animation: rows only appear after
 * their actual pixels have arrived and decoded.
 */
class ProgressiveTileImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {
    private data class Tile(val bitmap: Bitmap, val top: Int)

    private val tiles = mutableListOf<Tile>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
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
        setImageDrawable(null)
        invalidate()
    }

    fun appendDecodedTile(bitmap: Bitmap, top: Int) {
        if (!tileMode) {
            bitmap.recycle()
            return
        }
        tiles += Tile(bitmap, top)
        invalidate()
    }

    fun showCompleteDrawable(drawable: Drawable?) {
        clearTiles()
        tileMode = false
        setImageDrawable(drawable)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!tileMode || sourceWidth <= 0 || sourceHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val left = (width - drawnWidth) / 2f
        val topOffset = (height - drawnHeight) / 2f
        for (tile in tiles) {
            val destination = RectF(
                left,
                topOffset + tile.top * scale,
                left + drawnWidth,
                topOffset + (tile.top + tile.bitmap.height) * scale
            )
            canvas.drawBitmap(tile.bitmap, null, destination, paint)
        }
    }

    private fun clearTiles() {
        tiles.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        tiles.clear()
        sourceWidth = 0
        sourceHeight = 0
    }

    override fun onDetachedFromWindow() {
        clearTiles()
        super.onDetachedFromWindow()
    }
}
