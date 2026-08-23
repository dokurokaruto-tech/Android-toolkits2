package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 画像生成ビルダー画面の下部にオーバーレイする「選択中カード」ストリップ。
 *
 * - 背景は透明。レイアウトスペースを取らず、コンテンツの上に浮く。
 * - 開閉は中央上のハンドルだけ。カード本体をスワイプしても動かない。
 * - ハンドルを下へ → 折りたたみ（履歴・グラバーとカード上端だけ残る）
 * - ハンドルを上へ → 展開（指の動きに追従）
 * - 展開中のカードタップ → 選択解除（子RecyclerViewが処理）
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

    private var downRawY = 0f
    private var startTranslationY = 0f
    private var isDragging = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        bindHandle()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindHandle() {
        val handle = findViewById<View>(R.id.builder_strip_handle) ?: return
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    startTranslationY = translationY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (abs(dy) > touchSlop) isDragging = true
                    if (isDragging) {
                        translationY = (startTranslationY + dy).coerceIn(0f, collapsedOffset().coerceAtLeast(1f))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        val maxT = collapsedOffset().coerceAtLeast(1f)
                        if (translationY > maxT / 2f) collapse() else expand()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (isCollapsed) expand() else collapse()
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isCollapsed) translationY = collapsedOffset()
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

    fun refreshPeek() {
        if (!isCollapsed) return
        animate().cancel()
        translationY = collapsedOffset()
    }

    private fun collapsedOffset(): Float =
        (height - peekHeight()).toFloat().coerceAtLeast(0f)

    private fun peekHeight(): Int {
        val header = findViewById<View>(R.id.builder_strip_header)
        val cards = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_selected_cards)
        val hasCards = (cards?.adapter?.itemCount ?: 0) > 0
        return BuilderStripStatePolicy.peekHeight(
            headerHeight = header?.height ?: 0,
            cardPeek = BuilderStripStatePolicy.cardPeek(hasCards, cardPeekPx),
            fallbackHeader = fallbackHeaderPx
        )
    }
}
