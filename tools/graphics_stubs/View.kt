// Standalone harness only; minimal stand-ins for the Android view APIs used by
// BocchiLive2dStageView so the file can be type checked without the SDK.
package android.view

class MotionEvent {
    val actionMasked: Int = 0
    val x: Float = 0f
    val y: Float = 0f

    companion object {
        const val ACTION_DOWN = 0
    }
}

open class View(context: android.content.Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) {
    val width: Int = 1080
    val height: Int = 1920

    // android.view.View#getContext() は Kotlin からは context として見える
    open val context: android.content.Context = context

    open fun onAttachedToWindow() = Unit
    open fun onDetachedFromWindow() = Unit
    open fun onTouchEvent(event: MotionEvent): Boolean = false
    open fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = Unit
    open fun onDraw(canvas: android.graphics.Canvas) = Unit

    fun invalidate() = Unit
    fun postFrameCallback(callback: Choreographer.FrameCallback) = Unit
    fun removeFrameCallback(callback: Choreographer.FrameCallback) = Unit
}

object Choreographer {
    fun getInstance(): Choreographer = Choreographer

    fun postFrameCallback(callback: FrameCallback) = Unit
    fun removeFrameCallback(callback: FrameCallback) = Unit

    interface FrameCallback {
        fun doFrame(frameTimeNanos: Long)
    }
}
