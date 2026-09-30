package com.example.kennys_dokidoki_wallpaper

import java.time.LocalDate
import kotlin.math.sqrt

/**
 * カレンダーの升目と、書いた量を表す緑の濃さを決める計算。
 *
 *   日曜始まりの7列。月初の前は空白で埋める。
 *   1  2  3  4  5  6  7   <- 1日が水曜なら先頭に3つの空白
 */
object DiaryCalendarPolicy {

    const val COLUMNS = 7

    /** これだけ書けば緑は最も濃い。日記としては十分に長い量 */
    const val FULL_GREEN_CHARS = 1200

    private const val MIN_VISIBLE_ALPHA = 0.18f

    /** 1か月分の升目。null は前月・翌月にあたる空白 */
    fun monthCells(year: Int, month: Int): List<Int?> {
        val first = LocalDate.of(year, month, 1)
        val blanks = first.dayOfWeek.value % COLUMNS // 月曜=1 ... 日曜=7 -> 日曜=0
        val days = first.lengthOfMonth()
        return List(blanks) { null } + (1..days).toList()
    }

    /** 書いた文字数から緑の濃さを出す。少ないほど薄く、多いほど濃い */
    fun greenAlpha(charCount: Int): Float {
        if (charCount <= 0) {
            return 0f
        }
        // 短い日記でも見える程度から始め、長くなるほど詰まって濃くなる
        val ratio = sqrt(charCount.toFloat() / FULL_GREEN_CHARS).coerceIn(0f, 1f)
        return MIN_VISIBLE_ALPHA + (1f - MIN_VISIBLE_ALPHA) * ratio
    }

    fun alphaToAlphaByte(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * 255).toInt()

    /** 「前の月」「次の月」への移動。12月の次は翌年1月 */
    fun shiftMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val shifted = LocalDate.of(year, month, 1).plusMonths(delta.toLong())
        return shifted.year to shifted.monthValue
    }

    fun monthTitle(year: Int, month: Int): String = "${year}年${month}月"
}
