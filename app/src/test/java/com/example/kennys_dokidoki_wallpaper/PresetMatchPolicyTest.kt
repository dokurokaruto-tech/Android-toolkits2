package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetMatchPolicyTest {
    private val preset = Preset(
        id = "p1",
        name = "portrait",
        category = "test",
        activePromptStates = mapOf("card-a" to 1, "card-b" to 2),
        width = 720,
        height = 1280,
        steps = 20,
        batchCount = 3,
        sampler = "Euler a",
        randomEnabledCategories = setOf("hair")
    )

    @Test
    fun exactBuilderStateMatches() {
        assertTrue(
            PresetMatchPolicy.matches(
                preset,
                selectionLevels = mapOf("card-b" to 2, "card-a" to 1),
                randomEnabledCategories = setOf("hair"),
                width = 720,
                height = 1280,
                steps = 20,
                batchCount = 3,
                sampler = "Euler a"
            )
        )
    }

    @Test
    fun deletedCardIdsDoNotBreakEffectiveMatchAfterPresetTap() {
        val presetWithDeletedCard = preset.copy(
            activePromptStates = preset.activePromptStates + ("deleted-card" to 1)
        )
        assertTrue(
            PresetMatchPolicy.matches(
                presetWithDeletedCard,
                selectionLevels = preset.activePromptStates,
                randomEnabledCategories = setOf("hair"),
                width = 720,
                height = 1280,
                steps = 20,
                batchCount = 3,
                sampler = "Euler a",
                availableCardIds = setOf("card-a", "card-b")
            )
        )
    }

    @Test
    fun cardLevelOrGenerationSettingDifferenceDoesNotMatch() {
        assertFalse(
            PresetMatchPolicy.matches(
                preset,
                selectionLevels = mapOf("card-a" to 1, "card-b" to 3),
                randomEnabledCategories = setOf("hair"),
                width = 720,
                height = 1280,
                steps = 20,
                batchCount = 3,
                sampler = "Euler a"
            )
        )
        assertFalse(
            PresetMatchPolicy.matches(
                preset,
                selectionLevels = preset.activePromptStates,
                randomEnabledCategories = setOf("hair"),
                width = 720,
                height = 1280,
                steps = 30,
                batchCount = 3,
                sampler = "Euler a"
            )
        )
    }
}
