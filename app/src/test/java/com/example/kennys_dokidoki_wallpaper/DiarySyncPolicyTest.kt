package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarySyncPolicyTest {

    @Test
    fun driveNamesMapBackToLocalNames() {
        assertEquals("2026-09-30.json", DiarySyncPolicy.entryFileName("2026-09-30"))
        assertEquals("2026-09-30", DiarySyncPolicy.dateKeyOf("2026-09-30.json"))
        assertNull(DiarySyncPolicy.dateKeyOf("img_abc.img"))
        assertEquals("abc.img", DiarySyncPolicy.localImageName("img_abc.img"))
        assertNull(DiarySyncPolicy.localImageName("2026-09-30.json"))
    }

    @Test
    fun newerSideWins() {
        val local = 1_000_000L
        val remoteNewer = local + DiarySyncPolicy.CLOCK_SKEW_MS + 1
        assertTrue(DiarySyncPolicy.shouldPull(local, remoteNewer))
        assertFalse(DiarySyncPolicy.shouldPush(local, remoteNewer))
        assertTrue(DiarySyncPolicy.shouldPush(remoteNewer, local))
    }

    @Test
    fun tinyClockDriftIsIgnored() {
        val local = 1_000_000L
        val remote = local + DiarySyncPolicy.CLOCK_SKEW_MS - 1
        assertFalse(DiarySyncPolicy.shouldPull(local, remote))
        assertFalse(DiarySyncPolicy.shouldPush(local, remote))
    }

    @Test
    fun driveTimestampsParse() {
        assertEquals(0L, DriveTimes.toEpochMs(null))
        assertEquals(0L, DriveTimes.toEpochMs("こわれた"))
        assertEquals(1_759_190_400_000L, DriveTimes.toEpochMs("2025-09-30T00:00:00Z"))
    }
}
