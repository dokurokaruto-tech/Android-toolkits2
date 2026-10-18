package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPowerModePolicyTest {

    @Test
    fun `no started activity means sleeping`() {
        assertEquals(AppPowerModePolicy.Mode.SLEEPING, AppPowerModePolicy.modeFor(0))
        assertEquals(AppPowerModePolicy.Mode.FOREGROUND, AppPowerModePolicy.modeFor(1))
        assertEquals(AppPowerModePolicy.Mode.FOREGROUND, AppPowerModePolicy.modeFor(3))
    }

    @Test
    fun `activity count never dips below zero`() {
        assertEquals(0, AppPowerModePolicy.countAfter(0, -1))
        assertEquals(1, AppPowerModePolicy.countAfter(0, 1))
        assertEquals(0, AppPowerModePolicy.countAfter(1, -1))
        assertEquals(2, AppPowerModePolicy.countAfter(2, 0))
    }

    @Test
    fun `sleeping stills every periodic worker`() {
        val mode = AppPowerModePolicy.Mode.SLEEPING
        assertFalse(AppPowerModePolicy.shouldHeartbeat(mode))
        assertFalse(AppPowerModePolicy.shouldPollLibrary(mode))
        assertTrue(AppPowerModePolicy.discardsCaches(mode))
    }

    @Test
    fun `foreground allows heartbeat and polling`() {
        val mode = AppPowerModePolicy.Mode.FOREGROUND
        assertTrue(AppPowerModePolicy.shouldHeartbeat(mode))
        assertTrue(AppPowerModePolicy.shouldPollLibrary(mode))
        assertFalse(AppPowerModePolicy.discardsCaches(mode))
    }
}
