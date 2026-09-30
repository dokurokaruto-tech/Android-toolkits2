package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import kotlin.math.abs

/**
 * 日記の用紙に貼った写真をまとめて面倒みる層。
 * 画面側は「置く・並びを取り出す・消す」だけを呼び、
 * ドラッグやピンチの生の座標計算はここで閉じる。
 */
class DiaryPhotoLayer(
    private val canvas: FrameLayout,
    private val onDeleteRequest: (DiaryPhotoHandle) -> Unit
) {

    private val handles = mutableListOf<DiaryPhotoHandle>()
    private val longPress = Handler(Looper.getMainLooper())

    fun restore(photos: List<DiaryPhoto>) {
        handles.forEach { canvas.removeView(it.view) }
        handles.clear()
        canvas.post { photos.forEach { add(it) } }
    }

    /** 用紙のどこに置くかは比率で受け取り、実ピクセルへ直して配置する */
    fun add(photo: DiaryPhoto) {
        val view = ImageView(canvas.context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "日記の画像"
        }
        val handle = DiaryPhotoHandle(view, photo)
        handles.add(handle)

        canvas.addView(view, FrameLayout.LayoutParams(1, FrameLayout.LayoutParams.WRAP_CONTENT))
        Glide.with(canvas.context)
            .load(DiaryStore.imageFile(canvas.context, photo.fileName))
            .into(view)

        bindGestures(handle)
        apply(handle)
    }

    fun snapshot(): List<DiaryPhoto> = handles.map { it.photo }

    fun remove(handle: DiaryPhotoHandle) {
        canvas.removeView(handle.view)
        handles.remove(handle)
    }

    /** 比率どおりの大きさと位置を View に反映する */
    private fun apply(handle: DiaryPhotoHandle) {
        val params = handle.view.layoutParams as FrameLayout.LayoutParams
        params.width = DiaryLayoutPolicy.toPx(handle.photo.widthRatio, canvas.width)
            .coerceAtLeast(MIN_SIZE_PX)
        handle.view.layoutParams = params
        handle.view.translationX = handle.photo.xRatio * canvas.width
        handle.view.translationY = handle.photo.yRatio * canvas.height
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures(handle: DiaryPhotoHandle) {
        val scaleDetector = ScaleGestureDetector(
            canvas.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    handle.photo = handle.photo.copy(
                        widthRatio = DiaryLayoutPolicy.resize(handle.photo.widthRatio, detector.scaleFactor)
                    )
                    apply(handle)
                    return true
                }
            }
        )

        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f

        handle.view.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = view.translationX
                    startY = view.translationY
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    view.bringToFront()
                    longPress.postDelayed({ onDeleteRequest(handle) }, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (scaleDetector.isInProgress) {
                        longPress.removeCallbacksAndMessages(null)
                        return@setOnTouchListener true
                    }
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > DRAG_SLOP_PX || abs(dy) > DRAG_SLOP_PX) {
                        longPress.removeCallbacksAndMessages(null)
                    }
                    view.translationX = startX + dx
                    view.translationY = startY + dy
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPress.removeCallbacksAndMessages(null)
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    commit(handle)
                }
            }
            true
        }
    }

    /** 指を離した位置を用紙内へ丸め、比率として覚え直す */
    private fun commit(handle: DiaryPhotoHandle) {
        val widthRatio = handle.photo.widthRatio
        val heightRatio = DiaryLayoutPolicy.toRatio(handle.view.height.toFloat(), canvas.height)
        val x = DiaryLayoutPolicy.clampPosition(
            DiaryLayoutPolicy.toRatio(handle.view.translationX, canvas.width), widthRatio
        )
        val y = DiaryLayoutPolicy.clampPosition(
            DiaryLayoutPolicy.toRatio(handle.view.translationY, canvas.height), heightRatio
        )
        handle.photo = handle.photo.copy(xRatio = x, yRatio = y)
        apply(handle)
    }

    private companion object {
        const val LONG_PRESS_MS = 600L
        const val DRAG_SLOP_PX = 12f
        const val MIN_SIZE_PX = 64
    }
}

/** 画面に出ている写真1枚と、その保存用の状態 */
class DiaryPhotoHandle(val view: View, var photo: DiaryPhoto)
