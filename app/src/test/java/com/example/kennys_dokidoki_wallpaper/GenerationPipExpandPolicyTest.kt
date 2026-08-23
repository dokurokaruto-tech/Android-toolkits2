package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GenerationPipExpandPolicyTest {
    @Test
    fun usesTokyoCalendarDate() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val calendar = Calendar.getInstance(tokyo)
        calendar.clear()
        calendar.timeZone = tokyo
        calendar.set(2026, Calendar.AUGUST, 23, 0, 30, 0)
        assertEquals("2026-08-23", GenerationPipExpandPolicy.todayDate(calendar.timeInMillis))
    }

    @Test
    fun stillTodayJustBeforeTokyoMidnight() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        val calendar = Calendar.getInstance(tokyo)
        calendar.clear()
        calendar.timeZone = tokyo
        calendar.set(2026, Calendar.AUGUST, 22, 23, 59, 0)
        assertEquals("2026-08-22", GenerationPipExpandPolicy.todayDate(calendar.timeInMillis))
    }

    @Test
    fun suppressesPipRelaunchRightAfterExpand() {
        GenerationPipExpandPolicy.markExpanding(1_000L)
        assertTrue(GenerationPipExpandPolicy.shouldSuppressPipRelaunch(1_100L))
        assertFalse(GenerationPipExpandPolicy.shouldSuppressPipRelaunch(10_000L))
    }

    @Test
    fun acceptsOnlyIsoFolderDates() {
        assertTrue(GenerationPipExpandPolicy.shouldAutoOpen("2026-08-23"))
        assertFalse(GenerationPipExpandPolicy.shouldAutoOpen(null))
        assertFalse(GenerationPipExpandPolicy.shouldAutoOpen(""))
        assertFalse(GenerationPipExpandPolicy.shouldAutoOpen("20260823"))
        assertFalse(GenerationPipExpandPolicy.shouldAutoOpen("not-a-date"))
    }
}
