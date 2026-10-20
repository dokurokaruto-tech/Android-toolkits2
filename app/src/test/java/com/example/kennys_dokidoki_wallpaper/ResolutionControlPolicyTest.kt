package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.Axis
import com.example.kennys_dokidoki_wallpaper.ResolutionControlPolicy.LockMode
import com.example.kennys_dokidoki_wallpaper.ResolutionControlPolicy.SnapMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionControlPolicyTest {

    @Test
    fun ratioPresetBecomesTheLockedRatioForEitherSlider() {
        val preset = BuilderGenSettingPolicy.SIZE_PRESETS.first()
        val initial = ResolutionControlPolicy.start(800, 1280)
        val locked = ResolutionControlPolicy.setLocked(initial, LockMode.LOCKED)
        val selected = ResolutionControlPolicy.selectRatio(locked, preset.width, preset.height)

        val widthMoved = ResolutionControlPolicy.update(selected, Axis.WIDTH, 734, SnapMode.CUSTOM, 10)
        assertEquals(730, widthMoved.width)
        assertEquals(1300, widthMoved.height)
        assertTrue(ResolutionControlPolicy.matchesPresetRatio(widthMoved, preset))

        val heightMoved = ResolutionControlPolicy.update(widthMoved, Axis.HEIGHT, 1304, SnapMode.CUSTOM, 10)
        assertEquals(730, heightMoved.width)
        assertEquals(1300, heightMoved.height)
    }

    @Test
    fun togglingLockCapturesTheCurrentRatio() {
        val initial = ResolutionControlPolicy.start(720, 1280)
        val locked = ResolutionControlPolicy.setLocked(initial, LockMode.LOCKED)
        val unlocked = ResolutionControlPolicy.setLocked(locked, LockMode.UNLOCKED)
        val resized = ResolutionControlPolicy.update(unlocked, Axis.WIDTH, 750, SnapMode.CUSTOM, 10)
        assertEquals(1280, resized.height)

        val relocked = ResolutionControlPolicy.setLocked(resized, LockMode.LOCKED)
        val heightMoved = ResolutionControlPolicy.update(relocked, Axis.HEIGHT, 1400, SnapMode.CUSTOM, 10)
        assertEquals(820, heightMoved.width)
        assertEquals(1400, heightMoved.height)
    }

    @Test
    fun sliderSnapModeSelectsTenOrEightPixelSteps() {
        val initial = ResolutionControlPolicy.start(720, 1280)

        val tenPixel = ResolutionControlPolicy.update(initial, Axis.WIDTH, 725, SnapMode.CUSTOM, 10)
        val eightPixel = ResolutionControlPolicy.update(initial, Axis.WIDTH, 725, SnapMode.EIGHT_PIXELS, 10)
        val twentyPixel = ResolutionControlPolicy.update(initial, Axis.WIDTH, 734, SnapMode.CUSTOM, 20)
        val locked = ResolutionControlPolicy.setLocked(initial, LockMode.LOCKED)
        val ratio = ResolutionControlPolicy.selectRatio(locked, 720, 1280)
        val linked = ResolutionControlPolicy.update(ratio, Axis.WIDTH, 734, SnapMode.CUSTOM, 20)

        assertEquals(730, tenPixel.width)
        assertEquals(728, eightPixel.width)
        assertEquals(740, twentyPixel.width)
        assertEquals(1320, linked.height)
    }

    @Test
    fun customSnapStepIsClampedToAnEditableRange() {
        assertEquals(10, BuilderGenSettingPolicy.sanitizeSnapStep(null))
        assertEquals(1, BuilderGenSettingPolicy.sanitizeSnapStep(0))
        assertEquals(256, BuilderGenSettingPolicy.sanitizeSnapStep(999))
    }
}
