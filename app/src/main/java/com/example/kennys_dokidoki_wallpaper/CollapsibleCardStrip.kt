package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * 画像生成ビルダー画面の下部にオーバーレイする「選択中カード」ストリップ。
 *
 * - 背景は透明。レイアウトスペースを取らず、コンテンツの上に浮く。
 * - スワイプ判定は中央ハンドルと、実在するカードの上だけ。余白は裏へ通す。
 * - 最初に上下と判定したら開閉だけ。最初に左右と判定したらカード送りだけ。
 * - 折りたたみ中のタップは「展開」のみ（選択解除しない）
 * - 履歴ボタンはカードの真上のヘッダーに置き、畳んでもカードに重ねず一緒に下へ残る。
 */
class CollapsibleCardStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isCollapsed = false
        private set

    var onCollapsedChanged: ((Boolean) -> Unit)? = null

    private val fallbackHeaderPx: Int = (24 * resources.displayMetrics.density).toInt()
    private val cardPeekPx: Int = (32 * resources.displayMetrics.density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationY = 0f
    private var axis = BuilderStripSwipePolicy.Axis.NONE
    private var hit = BuilderStripSwipePolicy.Hit.NONE
    private var isDragging = false
    private var gestureActive = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hit = resolveHit(ev)
                gestureActive = BuilderStripSwipePolicy.acceptsGesture(hit)
                if (!gestureActive) return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!gestureActive) return false
                val handled = super.dispatchTouchEvent(ev)
                gestureActive = false
                return handled
            }
            else -> if (!gestureActive) return false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isCollapsed) translationY = collapsedOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!BuilderStripSwipePolicy.acceptsSwipe(hit)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rememberDown(ev)
                if (isCollapsed) return true
            }
            MotionEvent.ACTION_MOVE -> {
                lockAxis(ev)
                if (BuilderStripSwipePolicy.shouldIntercept(isCollapsed, axis, hit)) {
                    isDragging = BuilderStripSwipePolicy.shouldMoveVertically(axis)
                    applyVerticalDrag(ev)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetGesture()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!BuilderStripSwipePolicy.acceptsSwipe(hit)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rememberDown(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lockAxis(event)
                if (BuilderStripSwipePolicy.shouldMoveVertically(axis)) {
                    isDragging = true
                    applyVerticalDrag(event)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging && BuilderStripSwipePolicy.shouldMoveVertically(axis)) {
                    val maxT = collapsedOffset().coerceAtLeast(1f)
                    if (translationY > maxT / 2f) collapse() else expand()
                } else if (isCollapsed && event.actionMasked == MotionEvent.ACTION_UP &&
                    !BuilderStripSwipePolicy.shouldScrollHorizontally(axis)
                ) {
                    expand()
                }
                resetGesture()
                return true
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

    fun refreshPeek() {
        if (!isCollapsed) return
        animate().cancel()
        translationY = collapsedOffset()
    }

    private fun rememberDown(ev: MotionEvent) {
        downRawX = ev.rawX
        downRawY = ev.rawY
        startTranslationY = translationY
        axis = BuilderStripSwipePolicy.Axis.NONE
        isDragging = false
    }

    private fun lockAxis(ev: MotionEvent) {
        axis = BuilderStripSwipePolicy.resolveAxis(
            dx = ev.rawX - downRawX,
            dy = ev.rawY - downRawY,
            touchSlop = touchSlop,
            current = axis
        )
    }

    private fun applyVerticalDrag(ev: MotionEvent) {
        if (!BuilderStripSwipePolicy.shouldMoveVertically(axis)) return
        val dy = ev.rawY - downRawY
        translationY = (startTranslationY + dy).coerceIn(0f, collapsedOffset().coerceAtLeast(1f))
    }

    private fun resetGesture() {
        axis = BuilderStripSwipePolicy.Axis.NONE
        isDragging = false
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

    private fun collapsedOffset(): Float =
        (height - peekHeight()).toFloat().coerceAtLeast(0f)

    private fun peekHeight(): Int {
        val header = findViewById<View>(R.id.builder_strip_header)
        val cards = findViewById<RecyclerView>(R.id.recycler_selected_cards)
        val hasCards = (cards?.adapter?.itemCount ?: 0) > 0
        return BuilderStripStatePolicy.peekHeight(
            headerHeight = header?.height ?: 0,
            cardPeek = BuilderStripStatePolicy.cardPeek(hasCards, cardPeekPx),
            fallbackHeader = fallbackHeaderPx
        )
    }

    private fun resolveHit(ev: MotionEvent): BuilderStripSwipePolicy.Hit =
        BuilderStripSwipePolicy.hit(
            onHistory = isTouchOnView(ev, findViewById(R.id.layout_builder_history)),
            onHandle = isTouchOnView(ev, findViewById(R.id.builder_strip_handle)),
            onCard = isTouchOnCard(ev)
        )

    private fun isTouchOnCard(ev: MotionEvent): Boolean {
        val rv = findViewById<RecyclerView>(R.id.recycler_selected_cards) ?: return false
        for (index in 0 until rv.childCount) {
            val child = rv.getChildAt(index)
            if (child.visibility == VISIBLE && isTouchOnView(ev, child)) return true
        }
        return false
    }

    private fun isTouchOnView(ev: MotionEvent, view: View?): Boolean {
        if (view == null || view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return BuilderStripSwipePolicy.contains(
            ev.rawX,
            ev.rawY,
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + view.width).toFloat(),
            (loc[1] + view.height).toFloat()
        )
    }
}
