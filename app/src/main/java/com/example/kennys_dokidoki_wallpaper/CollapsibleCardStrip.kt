package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 画像生成ビルダー画面の下部にオーバーレイする「選択中カード」ストリップ。
 *
 * - 背景は透明。レイアウトスペースを取らず、コンテンツの上に浮く。
 * - 上から下にスワイプ → 折りたたみ（グラバーだけ残る）
 * - 下から上にスワイプ or タップ → 展開（指の動きに追従）
 * - 折りたたみ中のタップは「展開」のみ（選択解除しない）
 * - 展開中のカードタップ → 選択解除（子RecyclerViewが処理）
 * - 履歴ボタンは中に置くので、畳むと一緒に下へ行く。
 */
class CollapsibleCardStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isCollapsed = false
        private set

    var onCollapsedChanged: ((Boolean) -> Unit)? = null

    private val peekHeightPx: Int = (24 * resources.displayMetrics.density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawY = 0f
    private var startTranslationY = 0f
    private var isDragging = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isCollapsed) translationY = collapsedOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isCollapsed) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = ev.rawY
                startTranslationY = translationY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.rawY - downRawY) > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                startTranslationY = translationY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                if (abs(dy) > touchSlop) isDragging = true
                if (isDragging) {
                    translationY = (startTranslationY + dy).coerceIn(0f, collapsedOffset().coerceAtLeast(1f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val maxT = collapsedOffset().coerceAtLeast(1f)
                    if (translationY > maxT / 2f) collapse() else expand()
                } else if (isCollapsed) {
                    expand()
                }
                isDragging = false
            }
        }
        return true
    }

    fun applyCollapsed(collapsed: Boolean, animate: Boolean) {
        setCollapsed(collapsed, notify = false)
        moveToState(animate)
    }

    fun collapse() {
        setCollapsed(true, notify = true)
        moveToState(animate = true)
    }

    fun expand() {
        setCollapsed(false, notify = true)
        moveToState(animate = true)
    }

    private fun setCollapsed(collapsed: Boolean, notify: Boolean) {
        if (isCollapsed == collapsed) return
        isCollapsed = collapsed
        if (notify) onCollapsedChanged?.invoke(collapsed)
    }

    private fun moveToState(animate: Boolean) {
        val target = if (isCollapsed) collapsedOffset() else 0f
        val apply = {
            if (animate) {
                animate().translationY(target).setDuration(250).start()
            } else {
                animate().cancel()
                translationY = target
            }
        }
        if (height == 0) post(apply) else apply()
    }

    private fun collapsedOffset(): Float = (height - peekHeightPx).toFloat().coerceAtLeast(0f)
}
