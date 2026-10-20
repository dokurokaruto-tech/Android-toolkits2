// Standalone harness only; minimal stand-ins for the Android graphics APIs used
// by BocchiLive2dStageView so the file can be type checked without the SDK.
package android.graphics

class Bitmap {
    val width: Int = 592
    val height: Int = 750
}

class BitmapFactory {
    companion object {
        fun decodeStream(stream: java.io.InputStream): Bitmap? = Bitmap()
    }
}

class Canvas {
    fun drawBitmapMesh(
        bitmap: Bitmap,
        meshWidth: Int,
        meshHeight: Int,
        verts: FloatArray,
        vertexOffset: Int,
        colors: IntArray?,
        colorOffset: Int,
        paint: Paint?
    ) = Unit

    fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) = Unit
    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, paint: Paint) = Unit
    fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = Unit
    fun drawPath(path: Path, paint: Paint) = Unit
    fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) = Unit
    fun drawText(text: String, x: Float, y: Float, paint: Paint) = Unit
    fun save(): Int = 0
    fun restore() = Unit
    fun rotate(degrees: Float, px: Float, py: Float) = Unit
}

class Color {
    companion object {
        const val TRANSPARENT = 0
        const val WHITE = -1

        fun red(color: Int): Int = (color shr 16) and 0xFF
        fun green(color: Int): Int = (color shr 8) and 0xFF
        fun blue(color: Int): Int = color and 0xFF
        fun rgb(r: Int, g: Int, b: Int): Int = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class DashPathEffect(intervals: FloatArray, phase: Float) : PathEffect()

open class PathEffect

class LinearGradient(
    x0: Float, y0: Float, x1: Float, y1: Float,
    colors: IntArray, positions: FloatArray?, tile: Shader.TileMode
) : Shader()

class RadialGradient(
    cx: Float, cy: Float, radius: Float,
    color0: Int, color1: Int, tile: Shader.TileMode
) : Shader()

open class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
}

class Paint(flags: Int = 0) {
    var alpha: Int = 255
    var color: Int = 0
    var colorFilter: ColorFilter? = null
    var pathEffect: PathEffect? = null
    var shader: Shader? = null
    var strokeCap: Cap = Cap.BUTT
    var strokeWidth: Float = 0f
    var style: Style = Style.FILL
    var letterSpacing: Float = 0f
    var textSize: Float = 0f
    var isFakeBoldText: Boolean = false

    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Style { FILL, STROKE, FILL_AND_STROKE }

    companion object {
        const val ANTI_ALIAS_FLAG = 1
        const val FILTER_BITMAP_FLAG = 2
    }
}

open class ColorFilter

class PorterDuffColorFilter(color: Int, mode: PorterDuff.Mode) : ColorFilter()

class PorterDuff {
    enum class Mode { SRC_ATOP, SRC_IN, SRC_OVER, ADD }
}

class Path {
    fun reset() = Unit
    fun moveTo(x: Float, y: Float) = Unit
    fun lineTo(x: Float, y: Float) = Unit
    fun quadTo(x0: Float, y0: Float, x1: Float, y1: Float) = Unit
    fun close() = Unit
}
