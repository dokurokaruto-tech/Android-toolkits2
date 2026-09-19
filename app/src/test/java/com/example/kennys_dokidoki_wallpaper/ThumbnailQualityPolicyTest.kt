package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailQualityPolicyTest {

    @Test
    fun qualityFallsBackToDefaultForUnknownValue() {
        assertEquals(
            ThumbnailQualityPolicy.DEFAULT_QUALITY,
            ThumbnailQualityPolicy.qualityOf("ULTRA")
        )
        assertEquals(
            ThumbnailQualityPolicy.DEFAULT_QUALITY,
            ThumbnailQualityPolicy.qualityOf(null)
        )
        assertEquals(
            ThumbnailQualityPolicy.Quality.LOW,
            ThumbnailQualityPolicy.qualityOf("LOW")
        )
    }

    @Test
    fun scalesShrinkMonotonically() {
        val scales = ThumbnailQualityPolicy.Quality.values().map { it.scale }
        assertTrue(scales[0] > scales[1] && scales[1] > scales[2])
    }

    @Test
    fun explicitStepsBeatQualityDefaults() {
        val medium = ThumbnailQualityPolicy.Quality.MEDIUM
        assertEquals(20, ThumbnailQualityPolicy.stepsOf(0, medium))
        assertEquals(35, ThumbnailQualityPolicy.stepsOf(35, medium))
    }

    @Test
    fun scaledSizeKeepsAspectRatioWithinRounding() {
        listOf(
            1080 to 1920,
            832 to 1216,
            1024 to 1024
        ).forEach { (width, height) ->
            val (w, h) = ThumbnailQualityPolicy.scaledSize(
                width,
                height,
                ThumbnailQualityPolicy.Quality.MEDIUM
            )
            val raw = width.toFloat() / height
            val scaled = w.toFloat() / h
            assertTrue(kotlin.math.abs(scaled / raw - 1f) < 0.02f)
            assertEquals(0, w % 8)
            assertEquals(0, h % 8)
        }
    }

    @Test
    fun scaledSizeClampsToSdMinimum() {
        val (w, h) = ThumbnailQualityPolicy.scaledSize(
            320,
            320,
            ThumbnailQualityPolicy.Quality.LOW
        )
        assertEquals(256, w)
        assertEquals(256, h)
    }

    @Test
    fun scaledSizeLeavesDegenerateInputAlone() {
        assertEquals(
            0 to 0,
            ThumbnailQualityPolicy.scaledSize(0, 0, ThumbnailQualityPolicy.Quality.HIGH)
        )
        assertEquals(
            -5 to 100,
            ThumbnailQualityPolicy.scaledSize(-5, 100, ThumbnailQualityPolicy.Quality.LOW)
        )
    }

    @Test
    fun stepsLabelExplainsFollowMode() {
        val low = ThumbnailQualityPolicy.Quality.LOW
        assertEquals("画質に従う（12）", ThumbnailQualityPolicy.stepsLabel(0, low))
        assertEquals("24", ThumbnailQualityPolicy.stepsLabel(24, low))
    }
}
