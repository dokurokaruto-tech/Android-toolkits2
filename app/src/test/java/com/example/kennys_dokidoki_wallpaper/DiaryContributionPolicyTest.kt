package com.example.kennys_dokidoki_wallpaper

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryContributionPolicyTest {

    @Test
    fun recentWeeksHaveSundayThroughSaturdayRowsAndEndAtTheCurrentWeek() {
        val today = LocalDate.of(2026, 10, 22) // 木曜日
        val weeks = DiaryContributionPolicy.recentWeeks(today, 4)

        assertEquals(4, weeks.size)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(DayOfWeek.SUNDAY, weeks.first().first().dayOfWeek)
        assertEquals(DayOfWeek.SATURDAY, weeks.last().last().dayOfWeek)
        assertEquals(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)), weeks.last().first())
    }

    @Test
    fun yearGridContainsEachDateOnceAndPadsOnlyOutsideTheYear() {
        val weeks = DiaryContributionPolicy.yearWeeks(2024)
        val dates = weeks.flatten().filterNotNull()

        assertTrue(weeks.size in 52..53)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(366, dates.size)
        assertEquals(366, dates.toSet().size)
        assertTrue(dates.all { it.year == 2024 })
        assertTrue(weeks.first().first() == null)
    }

    @Test
    fun contributionLevelsDistinguishEmptyShortAndLongEntries() {
        assertEquals(0, DiaryContributionPolicy.level(0, hasEntry = false))
        assertEquals(1, DiaryContributionPolicy.level(0, hasEntry = true))
        assertEquals(1, DiaryContributionPolicy.level(80))
        assertEquals(2, DiaryContributionPolicy.level(81))
        assertEquals(3, DiaryContributionPolicy.level(241))
        assertEquals(4, DiaryContributionPolicy.level(601))
    }

    @Test
    fun nonPositiveWeekCountReturnsAnEmptyGraph() {
        assertTrue(DiaryContributionPolicy.recentWeeks(LocalDate.of(2026, 1, 1), 0).isEmpty())
        assertFalse(DiaryContributionPolicy.yearWeeks(2026).isEmpty())
    }
}
