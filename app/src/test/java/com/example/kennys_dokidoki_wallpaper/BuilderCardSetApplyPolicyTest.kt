package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderCardSetApplyPolicyTest {

    private val source = GeneratedImagePresetPolicy.Source(
        cardStates = linkedMapOf("a" to 1, "b" to 2, "gone" to 3),
        randomPickedIds = setOf("b"),
        randomEnabledCategories = setOf("背景"),
        width = 720,
        height = 1280,
        steps = 20,
        sampler = "Euler a",
        thumbnail = "http://pc/x.png"
    )

    @Test
    fun asIsModeKeepsPicksFixedAndCategoriesOff() {
        val mode = GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
        val selection = BuilderCardSetApplyPolicy.selectionFor(source, mode, setOf("a", "b", "c"))
        assertEquals(mapOf("a" to 1, "b" to 2), selection)
        assertTrue(BuilderCardSetApplyPolicy.randomCategoriesFor(source, mode).isEmpty())
    }

    @Test
    fun randomizerModeDropsPicksAndKeepsCategories() {
        val mode = GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER
        val selection = BuilderCardSetApplyPolicy.selectionFor(source, mode, setOf("a", "b", "c"))
        assertEquals(mapOf("a" to 1), selection)
        assertEquals(setOf("背景"), BuilderCardSetApplyPolicy.randomCategoriesFor(source, mode))
    }

    @Test
    fun labelsCarryCountsAndMode() {
        assertTrue(BuilderCardSetApplyPolicy.requestedLabel(3).contains("3"))
        assertTrue(BuilderCardSetApplyPolicy.appliedLabel(3, emptySet()).contains("3"))
        assertTrue(BuilderCardSetApplyPolicy.appliedLabel(3, setOf("背景")).contains("1"))
        assertTrue(BuilderCardSetApplyPolicy.MODE_AS_IS_LABEL.isNotEmpty())
        assertTrue(BuilderCardSetApplyPolicy.MODE_RANDOMIZER_LABEL.isNotEmpty())
        assertTrue(BuilderCardSetApplyPolicy.MISSING_CARDS.isNotEmpty())
        assertTrue(BuilderCardSetApplyPolicy.EMPTY_CARDS.isNotEmpty())
    }
}
