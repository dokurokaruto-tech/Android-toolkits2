package com.example.kennys_dokidoki_wallpaper

import kotlin.math.abs

/**
 * カルーセルの見た目を決める計算。中央のアイコンほど大きく濃く、
 * 端へ離れるほど小さく薄くする。View に触らないので単体テストできる。
 *
 *   [ o ]  ( O )  [ o ]
 *    薄小   濃大   薄小
 */
object HomeCarouselPolicy {

    private const val MIN_SCALE = 0.72f
    private const val MIN_ALPHA = 0.40f

    /** 中央からの距離を 0(中央)〜1(1枚以上離れた) に正規化する */
    fun offsetRatio(itemCenter: Float, listCenter: Float, itemSpan: Float): Float {
        if (itemSpan <= 0f) {
            return 0f
        }
        return (abs(itemCenter - listCenter) / itemSpan).coerceIn(0f, 1f)
    }

    fun scale(offsetRatio: Float): Float =
        MIN_SCALE + (1f - MIN_SCALE) * (1f - offsetRatio.coerceIn(0f, 1f))

    fun alpha(offsetRatio: Float): Float =
        MIN_ALPHA + (1f - MIN_ALPHA) * (1f - offsetRatio.coerceIn(0f, 1f))

    /** 左右の余白。アイコン1つ分を中央に置き、隣を覗かせる */
    fun sidePadding(listWidth: Int, itemWidth: Int): Int =
        ((listWidth - itemWidth) / 2).coerceAtLeast(0)
}
