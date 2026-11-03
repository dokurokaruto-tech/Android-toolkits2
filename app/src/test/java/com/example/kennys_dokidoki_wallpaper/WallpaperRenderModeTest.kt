package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperRenderModeTest {
    @Test
    fun recognizesThreeJsPreference() {
        assertEquals(
            WallpaperRenderMode.THREE_JS,
            WallpaperRenderMode.fromPreference(WallpaperRenderMode.THREE_JS.preferenceValue)
        )
    }

    @Test
    fun missingOrUnknownPreferenceKeepsExistingImageMode() {
        assertEquals(WallpaperRenderMode.IMAGE, WallpaperRenderMode.fromPreference(null))
        assertEquals(WallpaperRenderMode.IMAGE, WallpaperRenderMode.fromPreference("future-mode"))
    }
}
