package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageListOrderingTest {

    @Test
    fun `pins home then chat images to the front`() {
        val items = listOf("a", "b", "home", "c", "chat", "d")
        val result = ImageListOrdering.pinToFront(items, listOf("home", "chat")) { it }
        assertEquals(listOf("home", "chat", "a", "b", "c", "d"), result)
    }

    @Test
    fun `same home and chat image appears only once`() {
        val items = listOf("x", "same", "y")
        val result = ImageListOrdering.pinToFront(items, listOf("same", "same")) { it }
        assertEquals(listOf("same", "x", "y"), result)
    }

    @Test
    fun `ignores pinned images that are not in the list`() {
        val items = listOf("a", "b")
        val result = ImageListOrdering.pinToFront(items, listOf("missing", "b")) { it }
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `null pinned entries are skipped`() {
        val items = listOf("a", "b", "c")
        val result = ImageListOrdering.pinToFront(items, listOf(null, "c", null)) { it }
        assertEquals(listOf("c", "a", "b"), result)
    }
}
