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
    fun tallContentIsCappedSoItStaysAPopup() {
        assertEquals(860, Md3PopupDialog.popupHeight(1000, 2000))
        assertEquals(400, Md3PopupDialog.popupHeight(1000, 400))
    }
}
