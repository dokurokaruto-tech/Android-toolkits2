package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.GeneratedImageReplayPolicy.SeedMode
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
    fun clampsBatchWithBuilderRules() {
        assertEquals(1, GeneratedImageReplayPolicy.clampBatch(null))
        assertEquals(1, GeneratedImageReplayPolicy.clampBatch(0))
        assertEquals(1, GeneratedImageReplayPolicy.clampBatch(-4))
        assertEquals(12, GeneratedImageReplayPolicy.clampBatch(12))
    }

    @Test
    fun acceptsZeroSeedButRejectsRandomMinusOne() {
        assertEquals(0L, GeneratedImageReplayPolicy.parseSeed(0))
        assertEquals(4242L, GeneratedImageReplayPolicy.parseSeed("4242"))
        assertNull(GeneratedImageReplayPolicy.parseSeed(-1))
        assertNull(GeneratedImageReplayPolicy.parseSeed(null))
    }

    @Test
    fun requiresOnlyPrompt() {
        assertEquals(
            GeneratedImageReplayPolicy.MISSING_PROMPT,
            GeneratedImageReplayPolicy.missingReason(GeneratedImageLifecycle.Draft(seed = 1L))
        )
        // シードが無くても「変える」側で再生成できるので弾かない。
        assertNull(
            GeneratedImageReplayPolicy.missingReason(
                GeneratedImageLifecycle.Draft(prompt = "1girl")
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

    @Test
    fun sameSeedStaysSingleRequest() {
        val recipe = GeneratedImageReplayPolicy.recipeFrom(
            GeneratedImageLifecycle.Draft(prompt = "1girl", seed = 777L)
        )!!
        val requests = GeneratedImageReplayPolicy.requests(recipe, 20, SeedMode.SAME, 4)
        assertEquals(1, requests.size)
        assertEquals(777L, requests.single().seed)
    }

    @Test
    fun newSeedFansOutWithoutSeedLikeNormalGeneration() {
        val recipe = GeneratedImageReplayPolicy.recipeFrom(
            GeneratedImageLifecycle.Draft(prompt = "1girl", negativePrompt = "blur", seed = 777L)
        )!!
        val requests = GeneratedImageReplayPolicy.requests(recipe, 30, SeedMode.NEW, 3)
        assertEquals(3, requests.size)
        requests.forEach {
            assertNull(it.seed)
            assertEquals("1girl", it.prompt)
            assertEquals("blur", it.negativePrompt)
            assertEquals(30, it.steps)
        }
        assertTrue(GeneratedImageReplayPolicy.summary(recipe, SeedMode.NEW).contains("ランダム"))
    }

    @Test
    fun seedlessRecipeReplaysOnlyWithNewSeed() {
        val recipe = GeneratedImageReplayPolicy.recipeFrom(
            GeneratedImageLifecycle.Draft(prompt = "1girl")
        )!!
        assertNull(recipe.seed)
        val requests = GeneratedImageReplayPolicy.requests(recipe, 20, SeedMode.SAME, 2)
        assertNull(requests.single().seed)
        assertTrue(GeneratedImageReplayPolicy.summary(recipe).contains("ランダム"))
    }
}
