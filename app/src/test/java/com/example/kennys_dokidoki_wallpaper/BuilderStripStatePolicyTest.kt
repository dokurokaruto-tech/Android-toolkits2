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
    fun peekKeepsHeaderAboveHiddenCards() {
        assertEquals(44, BuilderStripStatePolicy.peekHeight(headerHeight = 44, fallback = 24))
        assertEquals(24, BuilderStripStatePolicy.peekHeight(headerHeight = 0, fallback = 24))
    }
}
