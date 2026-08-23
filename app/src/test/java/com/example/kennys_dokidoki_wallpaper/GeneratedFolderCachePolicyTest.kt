package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedFolderCachePolicyTest {
    @Test
    fun roundTripsFolderRows() {
        val entries = listOf(
            GeneratedFolderCachePolicy.Entry("2026-08-24", 12, "http://pc/thumb.png"),
            GeneratedFolderCachePolicy.Entry("2026-08-23", 3, null)
        )
        assertEquals(entries, GeneratedFolderCachePolicy.decode(GeneratedFolderCachePolicy.encode(entries)))
    }

    @Test
    fun emptyAndBrokenJsonStayEmpty() {
        assertTrue(GeneratedFolderCachePolicy.decode(null).isEmpty())
        assertTrue(GeneratedFolderCachePolicy.decode("").isEmpty())
        assertTrue(GeneratedFolderCachePolicy.decode("{not-json}").isEmpty())
    }
}
