package com.example.kennys_dokidoki_wallpaper

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 生成中だけチャット入力枠を虹色に光らせる。
 * 表示/非表示はフェードで切り替え、点灯中は色が横に流れ続ける。
 */
internal class ChatInputGlow(private val target: View) {
    private companion object {
        const val FADE_MS = 600L
        const val CYCLE_MS = 2400L
        const val STROKE_DP = 3f
        const val CORNER_DP = 24f
    }

    private val density = target.resources.displayMetrics.density
    private val drawable = RainbowGlowDrawable(CORNER_DP * density, STROKE_DP * density).apply {
        alpha = 0
        callback = target
    }
    private var fade: ValueAnimator? = null
    private var active = false

    // 常時回し続けるが、alpha=0 の間は描画コストだけで見えない
    private val flow = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CYCLE_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { drawable.phase = it.animatedValue as Float }
    }

    init {
        target.overlay.add(drawable)
        target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            drawable.setBounds(0, 0, v.width, v.height)
        }
    }

    fun setActive(on: Boolean) {
        if (on == active) {
            return
        }
        active = on
        drawable.setBounds(0, 0, target.width, target.height)

        fade?.cancel()
        val to = if (on) 255 else 0
        fade = ValueAnimator.ofInt(drawable.alpha, to).apply {
            duration = FADE_MS
            addUpdateListener { drawable.alpha = it.animatedValue as Int }
            start()
        }

        if (on && !flow.isRunning) {
            flow.start()
        }
        if (!on) {
            target.postDelayed({ if (!active) flow.cancel() }, FADE_MS)
        }
    }
}
