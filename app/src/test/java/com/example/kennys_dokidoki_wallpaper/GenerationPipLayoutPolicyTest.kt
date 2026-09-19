package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln

class GenerationPipLayoutPolicyTest {

    @Test
    fun portraitImageStaysPortraitEvenWhenEnlarged() {
        val compact = GenerationPipLayoutPolicy.windowRatio(832, 1216, enlarged = false)
        val enlarged = GenerationPipLayoutPolicy.windowRatio(832, 1216, enlarged = true)
        assertTrue(compact.isPortrait)
        assertTrue(enlarged.isPortrait)
    }

    @Test
    fun landscapeImageStaysLandscapeEvenWhenEnlarged() {
        val compact = GenerationPipLayoutPolicy.windowRatio(1344, 768, enlarged = false)
        val enlarged = GenerationPipLayoutPolicy.windowRatio(1344, 768, enlarged = true)
        assertTrue(compact.isLandscape)
        assertTrue(enlarged.isLandscape)
    }

    @Test
    fun squareImageStaysSquare() {
        assertEquals(1f, GenerationPipLayoutPolicy.windowRatio(1024, 1024, false).value, 0.001f)
        assertEquals(1f, GenerationPipLayoutPolicy.windowRatio(1024, 1024, true).value, 0.001f)
    }

    @Test
    fun extremeRatiosAreClampedIntoSystemBounds() {
        val veryTall = GenerationPipLayoutPolicy.windowRatio(512, 2048, false)
        assertTrue(veryTall.isPortrait)
        assertTrue(veryTall.value >= GenerationPipLayoutPolicy.SYSTEM_MIN_RATIO)

        val veryWide = GenerationPipLayoutPolicy.windowRatio(2048, 512, false)
        assertTrue(veryWide.isLandscape)
        assertTrue(veryWide.value <= GenerationPipLayoutPolicy.SYSTEM_MAX_RATIO)
    }

    @Test
    fun enlargeMovesRatioTowardSquareButNeverAcrossIt() {
        listOf(
            832 to 1216,
            768 to 1344,
            1344 to 768,
            512 to 2048
        ).forEach { (width, height) ->
            val compact = GenerationPipLayoutPolicy.windowRatio(width, height, false)
            val enlarged = GenerationPipLayoutPolicy.windowRatio(width, height, true)
            assertTrue(abs(ln(enlarged.value)) < abs(ln(compact.value)))
        }
    }

    @Test
    fun unknownImageFallsBackToPortraitDefault() {
        listOf(0 to 0, -1 to 100, 100 to 0).forEach { (width, height) ->
            val ratio = GenerationPipLayoutPolicy.windowRatio(width, height, false)
            assertEquals(
                GenerationPipLayoutPolicy.FALLBACK_WIDTH.toFloat() /
                    GenerationPipLayoutPolicy.FALLBACK_HEIGHT,
                ratio.value,
                0.001f
            )
        }
    }

    @Test
    fun ratioIsReducedPositiveIntegersSafeForRational() {
        listOf(
            832 to 1216,
            1344 to 768,
            512 to 2048,
            0 to 0
        ).forEach { (width, height) ->
            val ratio = GenerationPipLayoutPolicy.windowRatio(width, height, true)
            assertTrue(ratio.width >= 1)
            assertTrue(ratio.height >= 1)
            assertEquals(ratio.value, ratio.width.toFloat() / ratio.height, 0.001f)
            assertEquals(1, gcd(ratio.width, ratio.height))
        }
    }

    @Test
    fun sameImageAlwaysYieldsSameRatio() {
        val first = GenerationPipLayoutPolicy.windowRatio(832, 1216, false)
        val second = GenerationPipLayoutPolicy.windowRatio(832, 1216, false)
        assertEquals(first, second)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val next = x % y
            x = y
            y = next
        }
        return x
    }
}
