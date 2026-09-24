package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.Axis
import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderGenSettingPolicyTest {

    @Test
    fun batchHasNoUpperCap() {
        assertEquals(11, BuilderGenSettingPolicy.clamp(Axis.BATCH, 11))
        assertEquals(64, BuilderGenSettingPolicy.clamp(Axis.BATCH, 64))
        assertEquals(999, BuilderGenSettingPolicy.clamp(Axis.BATCH, 999))
    }

    @Test
    fun batchKeepsOnlyTheFloor() {
        assertEquals(1, BuilderGenSettingPolicy.clamp(Axis.BATCH, 0))
        assertEquals(1, BuilderGenSettingPolicy.clamp(Axis.BATCH, -5))
        assertEquals(1, BuilderGenSettingPolicy.clamp(Axis.BATCH, null))
    }

    @Test
    fun batchSwipeSpanGrowsInsteadOfLimiting() {
        assertEquals(10, BuilderGenSettingPolicy.swipeMax(Axis.BATCH, 3))
        assertEquals(40, BuilderGenSettingPolicy.swipeMax(Axis.BATCH, 40))
        assertTrue(BuilderGenSettingPolicy.swipeMax(Axis.BATCH, 400) >= 400)
    }

    @Test
    fun stepsStillClampToTheirRange() {
        assertEquals(100, BuilderGenSettingPolicy.clamp(Axis.STEPS, 101))
        assertEquals(1, BuilderGenSettingPolicy.clamp(Axis.STEPS, 0))
        assertEquals(20, BuilderGenSettingPolicy.clamp(Axis.STEPS, null))
    }

    @Test
    fun sizesSnapToEight() {
        assertEquals(720, BuilderGenSettingPolicy.clamp(Axis.WIDTH, 723))
        assertEquals(1280, BuilderGenSettingPolicy.clamp(Axis.HEIGHT, 1277))
        assertEquals(4096, BuilderGenSettingPolicy.clamp(Axis.WIDTH, 99999))
        assertEquals(64, BuilderGenSettingPolicy.clamp(Axis.WIDTH, 12))
    }

    @Test
    fun lockedRatioFollowsTheMovedSide() {
        assertEquals(1280, BuilderGenSettingPolicy.linkedSize(720, 720, 1280))
        assertEquals(640, BuilderGenSettingPolicy.linkedSize(360, 720, 1280))
        assertEquals(1280, BuilderGenSettingPolicy.linkedSize(720, 0, 1280))
    }

    @Test
    fun sanitizeRepairsLoadedValuesButNotABigBatch() {
        val broken = BuilderGenSettingPolicy.Settings(723, 1277, 0, -3, " ")
        val clean = BuilderGenSettingPolicy.sanitize(broken)
        assertEquals(720, clean.width)
        assertEquals(1280, clean.height)
        assertEquals(1, clean.steps)
        assertEquals(1, clean.batch)
        assertEquals(BuilderGenSettingPolicy.DEFAULT_SAMPLER, clean.sampler)

        val heavy = BuilderGenSettingPolicy.Settings(720, 1280, 20, 250, "DPM2")
        assertEquals(250, BuilderGenSettingPolicy.sanitize(heavy).batch)
    }

    @Test
    fun onlyResolutionCarriesTwoNumberRows() {
        assertEquals(listOf(Axis.WIDTH, Axis.HEIGHT), BuilderGenSettingPolicy.axesOf(Kind.RESOLUTION))
        assertEquals(listOf(Axis.STEPS), BuilderGenSettingPolicy.axesOf(Kind.STEPS))
        assertEquals(listOf(Axis.BATCH), BuilderGenSettingPolicy.axesOf(Kind.BATCH))
        assertTrue(BuilderGenSettingPolicy.axesOf(Kind.SAMPLER).isEmpty())
    }

    @Test
    fun previewReadsLikeTheBuilderButton() {
        val settings = BuilderGenSettingPolicy.Settings(720, 1280, 20, 24, "Euler a")
        assertEquals("720 x 1280", BuilderGenSettingPolicy.preview(Kind.RESOLUTION, settings))
        assertEquals("Steps: 20", BuilderGenSettingPolicy.preview(Kind.STEPS, settings))
        assertEquals("Batch: 24", BuilderGenSettingPolicy.preview(Kind.BATCH, settings))
        assertEquals("Euler a", BuilderGenSettingPolicy.preview(Kind.SAMPLER, settings))
    }

    @Test
    fun presetChipsStayOnTheEightGrid() {
        BuilderGenSettingPolicy.SIZE_PRESETS.forEach { preset ->
            assertEquals(preset.width, BuilderGenSettingPolicy.clamp(Axis.WIDTH, preset.width))
            assertEquals(preset.height, BuilderGenSettingPolicy.clamp(Axis.HEIGHT, preset.height))
        }
    }

    @Test
    fun resetTouchesOnlyThatScreen() {
        val heavy = BuilderGenSettingPolicy.Settings(1024, 1024, 90, 500, "DDIM")
        val batchBack = BuilderGenSettingPolicy.reset(Kind.BATCH, heavy)
        assertEquals(1, batchBack.batch)
        assertEquals(500, heavy.batch)
        assertEquals(1024, batchBack.width)
        assertEquals("DDIM", batchBack.sampler)

        val sizeBack = BuilderGenSettingPolicy.reset(Kind.RESOLUTION, heavy)
        assertEquals(BuilderGenSettingPolicy.DEFAULT_WIDTH, sizeBack.width)
        assertEquals(90, sizeBack.steps)
    }

    @Test
    fun axisRoundTripsThroughSettings() {
        BuilderGenSettingPolicy.axesOf(Kind.RESOLUTION).forEach { axis ->
            val moved = BuilderGenSettingPolicy.with(
                BuilderGenSettingPolicy.Settings(720, 1280, 20, 3, "Euler a"),
                axis,
                BuilderGenSettingPolicy.clamp(axis, 1024)
            )
            assertEquals(1024, BuilderGenSettingPolicy.valueOf(moved, axis))
        }
        assertEquals(Axis.HEIGHT, BuilderGenSettingPolicy.pairedAxis(Axis.WIDTH))
        assertEquals(Axis.WIDTH, BuilderGenSettingPolicy.pairedAxis(Axis.HEIGHT))
        assertNull(BuilderGenSettingPolicy.pairedAxis(Axis.BATCH))
    }
}
