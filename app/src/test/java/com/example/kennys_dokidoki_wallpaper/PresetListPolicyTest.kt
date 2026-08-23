package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetListPolicyTest {
    @Test
    fun expandedCategoryEndsWithAddNewAfterCards() {
        val rows = PresetListPolicy.rows(
            categoryOrder = listOf("クイックプリセット", "夜"),
            presets = listOf("p1" to "クイックプリセット", "p2" to "夜", "p3" to "夜"),
            collapsed = emptySet()
        )
        assertEquals(
            listOf(
                PresetListPolicy.Row.Header("クイックプリセット"),
                PresetListPolicy.Row.Card("p1"),
                PresetListPolicy.Row.AddNew("クイックプリセット"),
                PresetListPolicy.Row.Header("夜"),
                PresetListPolicy.Row.Card("p2"),
                PresetListPolicy.Row.Card("p3"),
                PresetListPolicy.Row.AddNew("夜")
            ),
            rows
        )
    }

    @Test
    fun collapsedCategoryHidesCardsAndAddNew() {
        val rows = PresetListPolicy.rows(
            categoryOrder = listOf("夜"),
            presets = listOf("p1" to "夜"),
            collapsed = setOf("夜")
        )
        assertEquals(listOf(PresetListPolicy.Row.Header("夜")), rows)
    }

    @Test
    fun emptyExpandedCategoryStillShowsAddNew() {
        val rows = PresetListPolicy.rows(
            categoryOrder = listOf("未分類"),
            presets = emptyList(),
            collapsed = emptySet()
        )
        assertEquals(
            listOf(
                PresetListPolicy.Row.Header("未分類"),
                PresetListPolicy.Row.AddNew("未分類")
            ),
            rows
        )
    }
}
