package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerDeleteHoldPolicyTest {
    @Test
    fun progressStartsEmptyAndFillsAfterOneSecond() {
        assertEquals(0f, ViewerDeleteHoldPolicy.progress(0L), 0.0001f)
        assertEquals(0.5f, ViewerDeleteHoldPolicy.progress(500L), 0.0001f)
        assertEquals(1f, ViewerDeleteHoldPolicy.progress(1000L), 0.0001f)
        assertEquals(1f, ViewerDeleteHoldPolicy.progress(1500L), 0.0001f)
    }

    @Test
    fun confirmOnlyAfterAFullSecond() {
        assertFalse(ViewerDeleteHoldPolicy.isConfirmed(999L))
        assertTrue(ViewerDeleteHoldPolicy.isConfirmed(1000L))
    }

    @Test
    fun sweepStartsAtTopAndGoesClockwise() {
        assertEquals(-90f, ViewerDeleteHoldPolicy.START_ANGLE_DEGREES, 0.0001f)
        assertEquals(0f, ViewerDeleteHoldPolicy.sweepDegrees(0f), 0.0001f)
        assertEquals(90f, ViewerDeleteHoldPolicy.sweepDegrees(0.25f), 0.0001f)
        assertEquals(360f, ViewerDeleteHoldPolicy.sweepDegrees(1f), 0.0001f)
    }

    @Test
    fun fingerLeavingTheIconCancelsTheHold() {
        assertFalse(ViewerDeleteHoldPolicy.movedBeyondSlop(10f, 10f, 12f, 11f, 8f))
        assertTrue(ViewerDeleteHoldPolicy.movedBeyondSlop(10f, 10f, 30f, 10f, 8f))
    }
}
