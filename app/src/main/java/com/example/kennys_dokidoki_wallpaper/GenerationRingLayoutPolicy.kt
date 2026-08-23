package com.example.kennys_dokidoki_wallpaper

import kotlin.math.roundToInt

/**
 * 中断ボタンの「色の付いた実体」に進捗リングを密着させる配置。
 * MaterialButton の inset を除いた可視矩形の外側へ、全周同じ隙間で広げる。
 */
object GenerationRingLayoutPolicy {
    data class Spec(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val cornerRadius: Float
    )

    fun layout(
        buttonWidth: Int,
        buttonHeight: Int,
        insetLeft: Int,
        insetTop: Int,
        insetRight: Int,
        insetBottom: Int,
        buttonCornerRadius: Float,
        strokeWidth: Float,
        gap: Float
    ): Spec {
        val visibleW = (buttonWidth - insetLeft - insetRight).coerceAtLeast(1)
        val visibleH = (buttonHeight - insetTop - insetBottom).coerceAtLeast(1)
        val expand = (strokeWidth / 2f) + gap.coerceAtLeast(0f)
        val pillRadius = visibleH / 2f
        val baseCorner = when {
            buttonCornerRadius <= 0f -> pillRadius
            buttonCornerRadius >= pillRadius - 0.5f -> pillRadius
            else -> buttonCornerRadius
        }
        return Spec(
            left = (insetLeft - expand).roundToInt(),
            top = (insetTop - expand).roundToInt(),
            width = (visibleW + 2f * expand).roundToInt().coerceAtLeast(1),
            height = (visibleH + 2f * expand).roundToInt().coerceAtLeast(1),
            cornerRadius = baseCorner + gap.coerceAtLeast(0f)
        )
    }
}
