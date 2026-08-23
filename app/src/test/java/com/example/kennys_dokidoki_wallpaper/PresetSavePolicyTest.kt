package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetSavePolicyTest {
    @Test
    fun defaultNameUsesResolution() {
        assertEquals("720 x 1280", PresetSavePolicy.defaultName(720, 1280))
        assertEquals("1280 x 720", PresetSavePolicy.defaultName(1280, 720))
    }

    @Test
    fun categoryListPutsQuickPresetFirstAndKeepsExisting() {
        val categories = PresetSavePolicy.selectableCategories(listOf("未分類", "ポートレート", "  "))
        assertEquals(
            listOf("クイックプリセット", "未分類", "ポートレート"),
            categories
        )
        assertEquals("クイックプリセット", PresetSavePolicy.defaultCategory(categories))
    }

    @Test
    fun emptyExistingStillOffersQuickPreset() {
        val categories = PresetSavePolicy.selectableCategories(emptyList())
        assertEquals(listOf("クイックプリセット"), categories)
        assertEquals("クイックプリセット", PresetSavePolicy.defaultCategory())
    }

    @Test
    fun overwriteCopiesCurrentSelection() {
        val (cards, random) = PresetSavePolicy.overwriteSelection(
            mapOf("card-a" to 2, "card-b" to 1),
            setOf("髪")
        )
        assertEquals(mapOf("card-a" to 2, "card-b" to 1), cards)
        assertEquals(setOf("髪"), random)
    }
}
