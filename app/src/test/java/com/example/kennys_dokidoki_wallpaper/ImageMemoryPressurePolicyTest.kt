package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMemoryPressurePolicyTest {
    @Test
    fun heapRatioPicksABand() {
        assertEquals(ImageMemoryPressurePolicy.Band.CALM, ImageMemoryPressurePolicy.bandFromHeap(100, 400))
        assertEquals(ImageMemoryPressurePolicy.Band.WARM, ImageMemoryPressurePolicy.bandFromHeap(250, 400))
        assertEquals(ImageMemoryPressurePolicy.Band.HOT, ImageMemoryPressurePolicy.bandFromHeap(300, 400))
        assertEquals(ImageMemoryPressurePolicy.Band.CRITICAL, ImageMemoryPressurePolicy.bandFromHeap(360, 400))
    }

    @Test
    fun trimLevelWhileUsingTheAppIsNotIgnored() {
        assertEquals(
            ImageMemoryPressurePolicy.Band.WARM,
            ImageMemoryPressurePolicy.bandFromTrimLevel(ImageMemoryPressurePolicy.TRIM_RUNNING_MODERATE)
        )
        assertEquals(
            ImageMemoryPressurePolicy.Band.HOT,
            ImageMemoryPressurePolicy.bandFromTrimLevel(ImageMemoryPressurePolicy.TRIM_UI_HIDDEN)
        )
        assertEquals(
            ImageMemoryPressurePolicy.Band.CRITICAL,
            ImageMemoryPressurePolicy.bandFromTrimLevel(ImageMemoryPressurePolicy.TRIM_BACKGROUND)
        )
    }

    @Test
    fun originalBudgetStaysInAPhoneSizedWindow() {
        assertEquals(16L * 1024 * 1024, ImageMemoryPressurePolicy.originalBudgetBytes(80L * 1024 * 1024))
        assertEquals(64L * 1024 * 1024, ImageMemoryPressurePolicy.originalBudgetBytes(1024L * 1024 * 1024))
        assertEquals(32L * 1024 * 1024, ImageMemoryPressurePolicy.originalBudgetBytes(256L * 1024 * 1024))
    }

    @Test
    fun periodicCycleRunsAfterEnoughGridBinds() {
        assertTrue(ImageMemoryPressurePolicy.shouldRunPeriodicCycle(18, 1000))
        assertFalse(ImageMemoryPressurePolicy.shouldRunPeriodicCycle(3, 1000))
        assertTrue(ImageMemoryPressurePolicy.shouldRunPeriodicCycle(3, 12_000))
        assertFalse(ImageMemoryPressurePolicy.shouldRunPeriodicCycle(0, 60_000))
    }

    @Test
    fun evictionDropsOldPagesBeforeTheWorkingSet() {
        val slots = listOf(
            ImageMemoryPressurePolicy.Slot("old-a", 10),
            ImageMemoryPressurePolicy.Slot("old-b", 10),
            ImageMemoryPressurePolicy.Slot("now", 10),
            ImageMemoryPressurePolicy.Slot("next", 10)
        )
        val kept = ImageMemoryPressurePolicy.evictToBudget(
            lruOldestFirst = slots,
            keep = setOf("now", "next"),
            maxEntries = 5,
            maxBytes = 10_000
        )
        assertEquals(listOf("now", "next"), kept.map { it.key })
    }

    @Test
    fun evictionShrinksTheWorkingSetWhenTheHeapIsHot() {
        val slots = listOf(
            ImageMemoryPressurePolicy.Slot("a", 40),
            ImageMemoryPressurePolicy.Slot("b", 40),
            ImageMemoryPressurePolicy.Slot("c", 40)
        )
        val kept = ImageMemoryPressurePolicy.evictToBudget(
            lruOldestFirst = slots,
            keep = setOf("a", "b", "c"),
            maxEntries = 1,
            maxBytes = 10_000
        )
        assertEquals(listOf("c"), kept.map { it.key })
        assertTrue(
            ImageMemoryPressurePolicy.evictToBudget(
                slots,
                emptySet(),
                maxEntries = 0,
                maxBytes = 10_000
            ).isEmpty()
        )
    }

    @Test
    fun heartbeatAndDiskSweepFollowPressure() {
        assertTrue(ImageMemoryPressurePolicy.shouldRunHeartbeat(10_000))
        assertFalse(ImageMemoryPressurePolicy.shouldRunHeartbeat(1_000))
        assertFalse(ImageMemoryPressurePolicy.shouldSweepDisk(ImageMemoryPressurePolicy.Band.CALM))
        assertTrue(ImageMemoryPressurePolicy.shouldSweepDisk(ImageMemoryPressurePolicy.Band.HOT))
        assertTrue(ImageMemoryPressurePolicy.shouldClearGlideDisk(ImageMemoryPressurePolicy.Band.CRITICAL))
        assertFalse(ImageMemoryPressurePolicy.shouldClearGlideDisk(ImageMemoryPressurePolicy.Band.WARM))
        assertEquals(24L * 1024 * 1024, ImageMemoryPressurePolicy.glideMemoryBytes(80L * 1024 * 1024))
        assertEquals(8L * 1024 * 1024, ImageMemoryPressurePolicy.glideMemoryBytes(1_000))
    }

    @Test
    fun keepKeysSkipBlanks() {
        assertEquals(
            setOf("current", "side"),
            ImageMemoryPressurePolicy.keepKeys("current", listOf("", "side", "current"))
        )
    }
}
