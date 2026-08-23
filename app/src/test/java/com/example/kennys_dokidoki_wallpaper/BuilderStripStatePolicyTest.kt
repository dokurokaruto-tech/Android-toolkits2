package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderStripStatePolicyTest {
    @Test
    fun missingValueStartsExpanded() {
        assertFalse(BuilderStripStatePolicy.isCollapsed(null))
        assertFalse(BuilderStripStatePolicy.isCollapsed(false))
    }

    @Test
    fun storedCollapseIsRestored() {
        assertTrue(BuilderStripStatePolicy.isCollapsed(true))
        assertEquals("builder_selected_strip_collapsed", BuilderStripStatePolicy.KEY_COLLAPSED)
    }

    @Test
    fun peekKeepsHeaderAndASliverOfCards() {
        assertEquals(76, BuilderStripStatePolicy.peekHeight(headerHeight = 44, cardPeek = 32, fallbackHeader = 24))
        assertEquals(56, BuilderStripStatePolicy.peekHeight(headerHeight = 0, cardPeek = 32, fallbackHeader = 24))
        assertEquals(44, BuilderStripStatePolicy.peekHeight(headerHeight = 44, cardPeek = 0, fallbackHeader = 24))
    }

    @Test
    fun emptyStripDoesNotPeekMissingCards() {
        assertEquals(0, BuilderStripStatePolicy.cardPeek(hasCards = false, peekWhenVisible = 32))
        assertEquals(32, BuilderStripStatePolicy.cardPeek(hasCards = true, peekWhenVisible = 32))
    }
}
