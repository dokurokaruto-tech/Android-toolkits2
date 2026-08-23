package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationRingProgressPolicyTest {
    @Test
    fun singleImageKeepsBothRingsInSync() {
        val current = GenerationRingProgressPolicy.currentImage(0.4f, overall = 0.4f, completed = 0, total = 1)
        assertEquals(0.4f, current)
        assertEquals(0.4f, GenerationRingProgressPolicy.overall(0, 1, current))
    }

    @Test
    fun twoImagesReachHalfWhenFirstFinishes() {
        assertEquals(0.5f, GenerationRingProgressPolicy.overall(1, 2, 0f), 0.0001f)
    }

    @Test
    fun hundredImagesAdvanceOnePercentPerFinishedImage() {
        assertEquals(0.01f, GenerationRingProgressPolicy.overall(1, 100, 0f), 0.0001f)
    }

    @Test
    fun derivesCurrentImageWhenAgentOmitsIt() {
        val current = GenerationRingProgressPolicy.currentImage(
            rawCurrent = null,
            overall = 0.75f,
            completed = 1,
            total = 2
        )
        assertEquals(0.5f, current, 0.0001f)
    }

    @Test
    fun completedCountIsImagesAlreadyFinished() {
        assertEquals(0, GenerationRingProgressPolicy.completedFromBatchIndex(1))
        assertEquals(1, GenerationRingProgressPolicy.completedFromBatchIndex(2))
        assertEquals(0, GenerationRingProgressPolicy.completedFromBatchIndex(0))
    }
}
