package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 画像生成ビルダー画面の下部に常駐する「選択中カード」ストリップ。
 *
 * - 背景は透明（コンテンツが透けて見える）
 * - 上から下にスワイプ → 折りたたんで画面下部に隠す（グラバーだけ残る）
 * - 下から上にスワイプ or タップ → 展開して元に戻す
 * - 折りたたみ状態でのタップは「展開」のみ（カードの選択解除は起こさない）
 * - 展開状態でのカードタップ → 子のRecyclerViewが処理（選択解除）
 */
class CollapsibleCardStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var isCollapsed = false
        private set

    /** 折りたたみ時に見える高さ（グラバー分） */
    private val peekHeightPx: Int = (28 * resources.displayMetrics.density).toInt()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawY = 0f
    private var isDragging = false

    /**
     * 子Viewのタッチをインターセプトするかを判定。
     * - 折りたたみ中: 全てインターセプト（子に触らせない → 選択解除させない）
     * - 展開中: ドラッグ（スワイプ）のみインターセプト。タップは子に通す。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isCollapsed) return true

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.rawY - downRawY) > touchSlop) {
                    isDragging = true
                    return true // ドラッグ開始 → インターセプト
                }
            }
        }
        // 展開中のタップは子に通す（RecyclerView → アダプター → 選択解除）
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downRawY
                if (abs(dy) > touchSlop) isDragging = true
                if (isDragging) {
                    val maxT = (height - peekHeightPx).toFloat().coerceAtLeast(1f)
                    translationY = dy.coerceIn(0f, maxT)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    // ドラッグ終了 → 位置でスナップ
                    val maxT = (height - peekHeightPx).toFloat().coerceAtLeast(1f)
                    if (translationY > maxT / 2f) collapse() else expand()
                } else {
                    // タップ（ドラッグなし）
                    if (isCollapsed) {
                        // 折りたたみ中のタップ → 展開 only（選択解除しない）
                        expand()
                    }
                    // 展開中のタップ → ここには来ない（onInterceptTouchEventで子に通している）
                }
                isDragging = false
            }
        }
        return true
    }

    /** ストリップを下にスライドさせて折りたたむ（グラバーだけ残る） */
    fun collapse() {
        isCollapsed = true
        val target = (height - peekHeightPx).toFloat().coerceAtLeast(0f)
        animate()
            .translationY(target)
            .setDuration(250)
            .withEndAction { isCollapsed = true }
            .start()
    }

    /** ストリップを上にスライドさせて完全に表示する */
    fun expand() {
        isCollapsed = false
        animate()
            .translationY(0f)
            .setDuration(250)
            .withEndAction { isCollapsed = false }
            .start()
    }
}
