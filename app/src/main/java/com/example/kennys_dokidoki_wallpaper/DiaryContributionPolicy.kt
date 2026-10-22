package com.example.kennys_dokidoki_wallpaper

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** GitHub風の日記ヒートマップを日曜始まりの7段で並べるための計算。 */
internal object DiaryContributionPolicy {

    const val MINI_WEEK_COUNT = 24
    val weekdayLabels = listOf("日", "月", "火", "水", "木", "金", "土")

    /** 古い週から新しい週へ、各列を日曜から土曜の順で返す。 */
    fun recentWeeks(endDate: LocalDate, weekCount: Int = MINI_WEEK_COUNT): List<List<LocalDate>> {
        if (weekCount <= 0) return emptyList()
        val currentSunday = endDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val firstSunday = currentSunday.minusWeeks((weekCount - 1).toLong())
        return List(weekCount) { week ->
            val sunday = firstSunday.plusWeeks(week.toLong())
            List(7) { day -> sunday.plusDays(day.toLong()) }
        }
    }

    /** 指定年の1月から12月まで。年外の端の日はnullにする。 */
    fun yearWeeks(year: Int): List<List<LocalDate?>> {
        val firstSunday = LocalDate.of(year, 1, 1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val lastSaturday = LocalDate.of(year, 12, 31)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val weekCount = ChronoUnit.WEEKS.between(firstSunday, lastSaturday).toInt() + 1
        return List(weekCount) { week ->
            val sunday = firstSunday.plusWeeks(week.toLong())
            List(7) { day ->
                sunday.plusDays(day.toLong()).takeIf { it.year == year }
            }
        }
    }

    /** 日記が存在する日は文字が短くても必ず最初の段階を点灯させる。 */
    fun level(charCount: Int, hasEntry: Boolean = charCount > 0): Int {
        if (!hasEntry) return 0
        return when {
            charCount <= 80 -> 1
            charCount <= 240 -> 2
            charCount <= 600 -> 3
            else -> 4
        }
    }
}
