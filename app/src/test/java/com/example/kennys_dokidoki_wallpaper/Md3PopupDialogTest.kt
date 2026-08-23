package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Md3PopupDialogTest {
    @Test
    fun popupIsNarrowerThanTheScreen() {
        assertEquals(920, Md3PopupDialog.popupWidth(1000))
        assertTrue(Md3PopupDialog.popupWidth(1080) < 1080)
    }

    @Test
    fun editorsShareATallUnifiedHeight() {
        val screen = 2400
        val height = Md3PopupDialog.popupHeight(screen)
        assertEquals(2208, height)
        assertEquals(height, Md3PopupDialog.popupHeight(screen))
        assertTrue(height < screen)
        assertTrue(height >= (screen * 0.90f).toInt())
    }
}
