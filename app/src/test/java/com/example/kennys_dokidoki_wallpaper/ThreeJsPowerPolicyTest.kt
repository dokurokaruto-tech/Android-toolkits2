package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeJsPowerPolicyTest {

    @Test
    fun presetsRoundTripCleanly() {
        for (preset in listOf(
            ThreeJsPowerPreset.QUALITY,
            ThreeJsPowerPreset.BALANCED_ECO,
            ThreeJsPowerPreset.MAX_SAVER
        )) {
            val cfg = ThreeJsPowerPolicy.presetConfig(preset)
            assertEquals(preset, ThreeJsPowerPolicy.detectPreset(cfg))
        }

        val tweaked = ThreeJsPowerPolicy.defaultConfig().copy(
            frameRateCap = ThreeJsFrameRateCap.FPS_12
        )
        assertEquals(ThreeJsPowerPreset.CUSTOM, ThreeJsPowerPolicy.detectPreset(tweaked))
    }

    @Test
    fun stressEscalatesEcoPlan() {
        val qualityCfg = ThreeJsPowerPolicy.presetConfig(ThreeJsPowerPreset.QUALITY)
        val stressed = ThreeJsPowerPolicy.resolveEffectivePlan(
            config = qualityCfg,
            systemStress = SystemPowerStress.SAVER_OR_THERMAL,
            homeTouch = HomeTouchState.ACTIVE
        )
        assertTrue(stressed.frameIntervalMs >= ThreeJsPowerPolicy.STRESS_MIN_INTERVAL_MS)
        assertEquals(ThreeJsResolutionTier.ECO_960.maxEdgePx, stressed.maxRenderEdgePx)
        assertEquals(1, stressed.maxPixelRatio)
        assertEquals(ThreeJsGpuQuality.ULTRA_ECO_NO_SHADOW, stressed.gpuQuality)
        assertEquals(ThreeJsBackgroundMode.PAUSE_PURGE_SCENE, stressed.backgroundMode)
        assertEquals(ThreeJsIdlePurgeDelay.AFTER_30S.delayMs, stressed.idlePurgeDelayMs)
    }

    @Test
    fun stressFreezesWhenRequested() {
        val saverCfg = ThreeJsPowerPolicy.presetConfig(ThreeJsPowerPreset.MAX_SAVER)
        val stressed = ThreeJsPowerPolicy.resolveEffectivePlan(
            config = saverCfg,
            systemStress = SystemPowerStress.SAVER_OR_THERMAL,
            homeTouch = HomeTouchState.ACTIVE
        )
        assertEquals(LoopExecutionMode.SINGLE_FRAME_FREEZE, stressed.loopMode)
        assertEquals(ThreeJsBackgroundMode.DEEP_STOP_PURGE_ALL, stressed.backgroundMode)
    }

    @Test
    fun homeIdleThrottlesAndFreezes() {
        val throttleCfg = ThreeJsPowerPolicy.defaultConfig().copy(
            frameRateCap = ThreeJsFrameRateCap.FPS_60,
            homeIdleMode = ThreeJsHomeIdleMode.THROTTLE_15S
        )
        val throttled = ThreeJsPowerPolicy.resolveEffectivePlan(
            config = throttleCfg,
            systemStress = SystemPowerStress.NORMAL,
            homeTouch = HomeTouchState.IDLE_TIMEOUT
        )
        assertEquals(ThreeJsPowerPolicy.HOME_IDLE_THROTTLE_MS, throttled.frameIntervalMs)
        assertEquals(LoopExecutionMode.CONTINUOUS, throttled.loopMode)

        val freezeCfg = ThreeJsPowerPolicy.defaultConfig().copy(
            homeIdleMode = ThreeJsHomeIdleMode.FREEZE_20S
        )
        val frozen = ThreeJsPowerPolicy.resolveEffectivePlan(
            config = freezeCfg,
            systemStress = SystemPowerStress.NORMAL,
            homeTouch = HomeTouchState.IDLE_TIMEOUT
        )
        assertEquals(LoopExecutionMode.SINGLE_FRAME_FREEZE, frozen.loopMode)
    }

    @Test
    fun jsProfileJsonEncodesFields() {
        val plan = ThreeJsPowerPolicy.defaultPlan()
        val json = ThreeJsPowerPolicy.toJsProfileJson(plan)
        assertTrue(json.contains("\"frameIntervalMs\":33"))
        assertTrue(json.contains("\"loopMode\":\"continuous\""))
        assertTrue(json.contains("\"maxPixelRatio\":1"))
        assertTrue(json.contains("\"antialias\":false"))
        assertTrue(json.contains("\"shadowMode\":\"basic\""))
        assertTrue(json.contains("\"powerPref\":\"low-power\""))
    }
}
