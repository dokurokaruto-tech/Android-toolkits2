package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageReplayPolicyTest {
    @Test
    fun clampsStepsToAgentRange() {
        assertEquals(1, GeneratedImageReplayPolicy.clampSteps(0))
        assertEquals(20, GeneratedImageReplayPolicy.clampSteps(null))
        assertEquals(150, GeneratedImageReplayPolicy.clampSteps(999))
        assertEquals(40, GeneratedImageReplayPolicy.clampSteps(40))
    }

    @Test
    fun acceptsZeroSeedButRejectsRandomMinusOne() {
        assertEquals(0L, GeneratedImageReplayPolicy.parseSeed(0))
        assertEquals(4242L, GeneratedImageReplayPolicy.parseSeed("4242"))
        assertNull(GeneratedImageReplayPolicy.parseSeed(-1))
        assertNull(GeneratedImageReplayPolicy.parseSeed(null))
    }

    @Test
    fun requiresPromptAndSeed() {
        assertEquals(
            GeneratedImageReplayPolicy.MISSING_PROMPT,
            GeneratedImageReplayPolicy.missingReason(GeneratedImageLifecycle.Draft(seed = 1L))
        )
        assertEquals(
            GeneratedImageReplayPolicy.MISSING_SEED,
            GeneratedImageReplayPolicy.missingReason(GeneratedImageLifecycle.Draft(prompt = "1girl"))
        )
        assertNull(
            GeneratedImageReplayPolicy.missingReason(
                GeneratedImageLifecycle.Draft(prompt = "1girl", seed = 99L)
            )
        )
    }

    @Test
    fun requestKeepsEverythingExceptSteps() {
        val recipe = GeneratedImageReplayPolicy.recipeFrom(
            GeneratedImageLifecycle.Draft(
                prompt = "1girl, smile",
                negativePrompt = "blur",
                width = 720,
                height = 1280,
                steps = 12,
                sampler = "DPM++ 2M",
                seed = 777L,
                tags = setOf("金髪"),
                cardStates = mapOf("card-a" to 2)
            )
        )!!
        val request = GeneratedImageReplayPolicy.request(recipe, 48)
        assertEquals("1girl, smile", request.prompt)
        assertEquals("blur", request.negativePrompt)
        assertEquals(720, request.width)
        assertEquals(1280, request.height)
        assertEquals(48, request.steps)
        assertEquals("DPM++ 2M", request.samplerName)
        assertEquals(777L, request.seed)
        assertEquals(listOf("金髪"), request.tags)
        assertEquals(mapOf("card-a" to 2), request.cardStates)
        assertTrue(GeneratedImageReplayPolicy.summary(recipe).contains("Seed 777"))
    }
}
