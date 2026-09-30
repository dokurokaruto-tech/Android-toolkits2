package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryCalendarPolicyTest {

    @Test
    fun monthStartsOnTheRightWeekday() {
        // 2026年9月1日は火曜。日曜始まりなので空白2つのあと1日が来る
        val cells = DiaryCalendarPolicy.monthCells(2026, 9)
        assertEquals(listOf(null, null, 1), cells.take(3))
        assertEquals(30, cells.last())
        assertEquals(32, cells.size)
    }

    @Test
    fun februaryLeapYearHasAllDays() {
        assertEquals(29, DiaryCalendarPolicy.monthCells(2024, 2).filterNotNull().size)
        assertEquals(28, DiaryCalendarPolicy.monthCells(2026, 2).filterNotNull().size)
    }

    @Test
    fun emptyDayHasNoGreen() {
        assertEquals(0f, DiaryCalendarPolicy.greenAlpha(0), 0.0001f)
        assertEquals(0f, DiaryCalendarPolicy.greenAlpha(-5), 0.0001f)
    }

    @Test
    fun greenGetsDarkerAsTextGrows() {
        val short = DiaryCalendarPolicy.greenAlpha(20)
        val medium = DiaryCalendarPolicy.greenAlpha(300)
        val long = DiaryCalendarPolicy.greenAlpha(DiaryCalendarPolicy.FULL_GREEN_CHARS)
        assertTrue(short in 0.01f..medium)
        assertTrue(medium < long)
        assertEquals(1f, long, 0.0001f)
        // 上限を超えても濃さは飽和したまま
        assertEquals(1f, DiaryCalendarPolicy.greenAlpha(100_000), 0.0001f)
    }

    @Test
    fun monthShiftWrapsTheYear() {
        assertEquals(2027 to 1, DiaryCalendarPolicy.shiftMonth(2026, 12, 1))
        assertEquals(2025 to 12, DiaryCalendarPolicy.shiftMonth(2026, 1, -1))
    }
}
