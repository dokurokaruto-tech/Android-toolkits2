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
    fun keepsLevelsAndDropsUnknownCards() {
        val selection = BuilderCardSetApplyPolicy.selectionFor(source, setOf("a", "b", "c"))
        assertEquals(mapOf("a" to 1, "b" to 2), selection)
    }

    @Test
    fun randomPicksBecomeFixedAndCategoriesTurnOff() {
        val selection = BuilderCardSetApplyPolicy.selectionFor(source, setOf("a", "b"))
        assertTrue(selection.containsKey("b"))
        assertTrue(BuilderCardSetApplyPolicy.randomCategoriesFor(source).isEmpty())
    }

    @Test
    fun labelsCarryCounts() {
        assertTrue(BuilderCardSetApplyPolicy.requestedLabel(3).contains("3"))
        assertTrue(BuilderCardSetApplyPolicy.appliedLabel(3).contains("3"))
        assertTrue(BuilderCardSetApplyPolicy.MISSING_CARDS.isNotEmpty())
        assertTrue(BuilderCardSetApplyPolicy.EMPTY_CARDS.isNotEmpty())
    }
}
