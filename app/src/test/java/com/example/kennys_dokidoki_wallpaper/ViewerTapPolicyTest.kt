package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerTapPolicyTest {
    @Test
    fun `stays a tap inside the slop`() {
        assertTrue(ViewerTapPolicy.isTap(0f, 0f, 16f))
        assertTrue(ViewerTapPolicy.isTap(12f, 8f, 16f))
        assertTrue(ViewerTapPolicy.isTap(-16f, 0f, 16f))
    }

    @Test
    fun `horizontal or vertical swipe is not a tap`() {
        assertFalse(ViewerTapPolicy.isTap(40f, 2f, 16f))
        assertFalse(ViewerTapPolicy.isTap(-2f, 40f, 16f))
        assertFalse(ViewerTapPolicy.isTap(20f, 20f, 16f))
    }

    @Test
    fun `returning to the start after a swipe is still a swipe`() {
        assertFalse(ViewerTapPolicy.isTapAfterTravel(40f, 16f))
        assertTrue(ViewerTapPolicy.isTapAfterTravel(8f, 16f))
    }

    @Test
    fun `left half uses the down position`() {
        assertTrue(ViewerTapPolicy.isLeftHalf(10f, 100f))
        assertFalse(ViewerTapPolicy.isLeftHalf(50f, 100f))
        assertFalse(ViewerTapPolicy.isLeftHalf(90f, 100f))
    }
}
