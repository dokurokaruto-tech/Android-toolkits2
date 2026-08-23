package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuilderStripSwipePolicyTest {
    @Test
    fun waitsUntilPastSlop() {
        assertEquals(
            BuilderStripSwipePolicy.Axis.NONE,
            BuilderStripSwipePolicy.resolveAxis(8f, 6f, touchSlop = 16, current = BuilderStripSwipePolicy.Axis.NONE)
        )
    }

    @Test
    fun firstVerticalLockStaysVertical() {
        val locked = BuilderStripSwipePolicy.resolveAxis(
            dx = 4f,
            dy = 30f,
            touchSlop = 16,
            current = BuilderStripSwipePolicy.Axis.NONE
        )
        assertEquals(BuilderStripSwipePolicy.Axis.VERTICAL, locked)
        assertEquals(
            BuilderStripSwipePolicy.Axis.VERTICAL,
            BuilderStripSwipePolicy.resolveAxis(80f, 2f, touchSlop = 16, current = locked)
        )
        assertTrue(BuilderStripSwipePolicy.shouldMoveVertically(locked))
        assertFalse(BuilderStripSwipePolicy.shouldScrollHorizontally(locked))
    }

    @Test
    fun firstHorizontalLockStaysHorizontal() {
        val locked = BuilderStripSwipePolicy.resolveAxis(
            dx = 28f,
            dy = 5f,
            touchSlop = 16,
            current = BuilderStripSwipePolicy.Axis.NONE
        )
        assertEquals(BuilderStripSwipePolicy.Axis.HORIZONTAL, locked)
        assertEquals(
            BuilderStripSwipePolicy.Axis.HORIZONTAL,
            BuilderStripSwipePolicy.resolveAxis(3f, 90f, touchSlop = 16, current = locked)
        )
        assertTrue(BuilderStripSwipePolicy.shouldScrollHorizontally(locked))
        assertFalse(BuilderStripSwipePolicy.shouldMoveVertically(locked))
        assertFalse(BuilderStripSwipePolicy.shouldIntercept(collapsed = false, axis = locked))
    }

    @Test
    fun collapsedStripAlwaysIntercepts() {
        assertTrue(
            BuilderStripSwipePolicy.shouldIntercept(
                collapsed = true,
                axis = BuilderStripSwipePolicy.Axis.NONE
            )
        )
    }
}
