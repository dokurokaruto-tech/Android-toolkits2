package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSetImagePickerTest {

    @Test
    fun `grid is three columns`() {
        assertEquals(3, ChatSetImagePicker.GRID_COLUMNS)
    }

    @Test
    fun `finds the current image id in the set list`() {
        val ids = listOf("a", "b", "c")
        assertEquals(1, ChatSetImagePicker.indexOfId(ids, "b"))
        assertEquals(-1, ChatSetImagePicker.indexOfId(ids, "missing"))
        assertEquals(-1, ChatSetImagePicker.indexOfId(ids, null))
    }

    @Test
    fun `only in-range taps are selectable`() {
        assertTrue(ChatSetImagePicker.canSelect(6, 0))
        assertTrue(ChatSetImagePicker.canSelect(6, 5))
        assertFalse(ChatSetImagePicker.canSelect(6, 6))
        assertFalse(ChatSetImagePicker.canSelect(0, 0))
        assertFalse(ChatSetImagePicker.canSelect(3, -1))
    }
}
