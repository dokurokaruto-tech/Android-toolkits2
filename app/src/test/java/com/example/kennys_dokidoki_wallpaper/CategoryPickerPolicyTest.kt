package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryPickerPolicyTest {
    @Test
    fun selectableKeepsOrderAndAddsCurrent() {
        assertEquals(
            listOf("衣装", "ポーズ", "背景"),
            CategoryPickerPolicy.selectable(listOf("衣装", "ポーズ"), "背景")
        )
    }

    @Test
    fun emptyListFallsBackToUncategorized() {
        assertEquals(
            listOf(CategoryPickerPolicy.FALLBACK),
            CategoryPickerPolicy.selectable(emptyList())
        )
    }

    @Test
    fun defaultSelectedPrefersExistingChoice() {
        assertEquals(
            "ポーズ",
            CategoryPickerPolicy.defaultSelected(listOf("衣装", "ポーズ"), "ポーズ")
        )
        assertEquals(
            "衣装",
            CategoryPickerPolicy.defaultSelected(listOf("衣装", "ポーズ"), "  ")
        )
    }
}
