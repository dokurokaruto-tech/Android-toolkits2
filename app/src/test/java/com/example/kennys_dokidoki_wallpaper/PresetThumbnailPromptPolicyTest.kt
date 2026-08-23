package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetThumbnailPromptPolicyTest {
    @Test
    fun registeredCardsStayAndRandomCategoryIsDrawn() {
        val girl = card("c1", "キャラ", "girl")
        val longHair = card("h1", "髪", "long hair")
        val shortHair = card("h2", "髪", "short hair")
        val preset = preset(
            cards = mapOf("c1" to 2),
            random = setOf("髪")
        )
        val prepared = PresetThumbnailPromptPolicy.prepare(
            preset = preset,
            roster = listOf(girl, longHair, shortHair),
            randomizerIncludedIds = setOf("h1", "h2"),
            chance = { 0 },
            pickIndex = { 1 }
        )!!
        assertTrue(prepared.prompt.contains("(girl:1.2)"))
        assertTrue(prepared.prompt.contains("short hair"))
        assertEquals(mapOf("c1" to 2, "h2" to 1), prepared.cardStates)
        assertEquals(setOf("h2"), prepared.randomPickedIds)
        assertEquals(setOf("髪"), prepared.randomEnabledCategories)
    }

    @Test
    fun randomOnlyPresetStillBuildsAPrompt() {
        val poseA = card("p1", "ポーズ", "standing")
        val poseB = card("p2", "ポーズ", "sitting")
        val prepared = PresetThumbnailPromptPolicy.prepare(
            preset = preset(cards = emptyMap(), random = setOf("ポーズ")),
            roster = listOf(poseA, poseB),
            randomizerIncludedIds = emptySet(),
            chance = { 0 },
            pickIndex = { 0 }
        )!!
        assertEquals("standing", prepared.prompt)
        assertEquals(setOf("p1"), prepared.randomPickedIds)
    }

    @Test
    fun emptyPresetWithoutRandomReturnsNull() {
        assertNull(
            PresetThumbnailPromptPolicy.prepare(
                preset = preset(cards = emptyMap(), random = emptySet()),
                roster = listOf(card("c1", "キャラ", "girl")),
                randomizerIncludedIds = emptySet(),
                chance = { 0 },
                pickIndex = { 0 }
            )
        )
    }

    @Test
    fun requestKeepsThumbnailPurpose() {
        val request = PresetThumbnailPromptPolicy.request(
            preset = preset(cards = mapOf("c1" to 1), random = emptySet(), steps = 28, sampler = "DPM++ 2M"),
            roster = listOf(card("c1", "キャラ", "girl")),
            randomizerIncludedIds = emptySet(),
            chance = { 0 },
            pickIndex = { 0 }
        )!!
        assertEquals("thumbnail", request.purpose)
        assertEquals("girl", request.prompt)
        assertEquals(28, request.steps)
        assertEquals("DPM++ 2M", request.samplerName)
        assertEquals(1080, request.width)
        assertEquals(1920, request.height)
    }

    private fun card(id: String, category: String, prompt: String) = PromptCard(
        id = id,
        label = id,
        mainPrompt = prompt,
        negativePrompt = "",
        category = category
    )

    private fun preset(
        cards: Map<String, Int>,
        random: Set<String>,
        steps: Int = 20,
        sampler: String = "Euler a"
    ) = Preset(
        id = "preset-1",
        name = "test",
        category = "未分類",
        activePromptStates = cards,
        width = 720,
        height = 1280,
        steps = steps,
        batchCount = 1,
        sampler = sampler,
        randomEnabledCategories = random
    )
}
