package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerChromePolicyTest {

    @Test
    fun `stays visible until three quiet seconds pass`() {
        assertFalse(ViewerChromePolicy.shouldHide(2_999L, 0L))
        assertFalse(ViewerChromePolicy.shouldHide(3_999L, 1_000L))
        assertTrue(ViewerChromePolicy.shouldHide(4_000L, 1_000L))
        assertTrue(ViewerChromePolicy.shouldHide(10_000L, 1_000L))
    }
}
