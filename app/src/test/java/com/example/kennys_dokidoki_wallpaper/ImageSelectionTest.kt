package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.*
import org.junit.Test

class ImageSelectionTest {
    @Test
    fun prependKeepsSameImage() {
        val selection = ImageSelection()
        selection.start(listOf("A", "B", "C"), 1)
        val updated = listOf("NEW", "A", "B", "C")
        selection.reconcile(updated)
        assertEquals(listOf(2), selection.indices(updated))
    }

    @Test
    fun rangeAnchorFollowsImage() {
        val selection = ImageSelection()
        selection.start(listOf("A", "B", "C", "D"), 1)
        val updated = listOf("NEW", "A", "B", "C", "D")
        selection.range(updated, 4)
        assertEquals(listOf(2, 3, 4), selection.indices(updated))
    }

    @Test
    fun vanishedAnchorIsNotReused() {
        val selection = ImageSelection()
        selection.start(listOf("A", "B", "C"), 1)
        selection.reconcile(listOf("A", "C", "D"))
        selection.range(listOf("A", "C", "D"), 2)
        assertEquals(listOf(2), selection.indices(listOf("A", "C", "D")))
    }

    @Test
    fun reorderAndDeletionAreSafe() {
        val selection = ImageSelection()
        selection.all(listOf("A", "B"))
        selection.reconcile(listOf("C", "B", "A"))
        assertEquals(listOf(1, 2), selection.indices(listOf("C", "B", "A")))
        selection.reconcile(listOf("C", "A"))
        assertEquals(listOf(1), selection.indices(listOf("C", "A")))
        selection.reconcile(emptyList())
        assertEquals(0, selection.size)
        selection.reconcile(listOf("A", "B"))
        assertEquals(0, selection.size)
    }

    @Test
    fun selectAllIsASnapshot() {
        val selection = ImageSelection()
        selection.all(listOf("A", "B", "B"))
        assertEquals(2, selection.size)
        selection.reconcile(listOf("NEW", "A", "B"))
        assertFalse(selection.contains("NEW"))
    }

    @Test
    fun stalePositionsAreIgnored() {
        val selection = ImageSelection()
        selection.start(listOf("A"), -1)
        selection.toggle(listOf("A"), 9)
        selection.range(emptyList(), 0)
        assertEquals(0, selection.size)
    }

    @Test
    fun identitySurvivesTokenRefresh() {
        val old = ImageSelection.key("http://pc/api/v1/files/2026-10-02/a.png?token=old")
        val refreshed = ImageSelection.key("http://new/api/v1/files/2026-10-02/a.png?token=new")
        assertEquals(old, refreshed)
        assertNotEquals(old, ImageSelection.key("http://pc/api/v1/files/2026-10-03/a.png"))
        assertNotEquals(ImageSelection.key("content://media/1?v=a"), ImageSelection.key("content://media/1?v=b"))
    }
}
