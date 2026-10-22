package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryPaletteTest {

    @Test
    fun colorPickerOffersManyDistinctPresetsIncludingTheDefaultPalette() {
        val presets = DiaryPaletteStore.presets

        assertTrue(presets.size >= 48)
        assertEquals(presets.size, presets.map { it.color }.distinct().size)
        assertTrue(presets.any { it.color == 0xFF000000.toInt() })
        assertTrue(presets.any { it.color == 0xFFFF2020.toInt() })
    }
}
