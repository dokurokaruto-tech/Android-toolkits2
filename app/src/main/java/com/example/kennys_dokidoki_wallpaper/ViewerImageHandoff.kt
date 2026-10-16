package com.example.kennys_dokidoki_wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import java.util.UUID

/** Binderへ全件一覧やBitmapを渡さない。消失時はIntentの現在画像へ復帰する。 */
internal object ViewerImageHandoff {
    private val albums = linkedMapOf<String, List<ImageEntry>>()
    private var preview: Pair<String, Bitmap>? = null

    fun album(entries: List<ImageEntry>): String {
        val key = UUID.randomUUID().toString()
        albums[key] = entries.toList()
        while (albums.size > 2) {
            albums.remove(albums.keys.first())
        }
        return key
    }

    fun replace(key: String, entries: List<ImageEntry>) {
        albums[key] = entries.toList()
        while (albums.size > 2) {
            albums.remove(albums.keys.first())
        }
    }

    fun entries(key: String?): List<ImageEntry>? = key?.let { albums[it] }

    fun capture(uri: String, drawable: Drawable?) {
        preview = null
        if (drawable == null || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) {
            return
        }
        val width = ImageMemoryPressurePolicy.GRID_THUMB_WIDTH
        val height = (width.toLong() * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt().coerceIn(1, 1024)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val oldBounds = android.graphics.Rect(drawable.bounds)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        drawable.bounds = oldBounds
        preview = uri to bitmap
    }

    fun preview(uri: String, resources: Resources): Drawable? {
        val value = preview ?: return null
        if (value.first != uri) {
            return null
        }
        preview = null
        return BitmapDrawable(resources, value.second)
    }
}
