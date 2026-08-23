package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedLibraryMergePolicyTest {
    @Test
    fun prependsOnlyUnknownUrlsInIncomingOrder() {
        val merged = GeneratedLibraryMergePolicy.prependNewUrls(
            existing = listOf("old-a", "old-b"),
            incoming = listOf("new-1", "old-a", "new-2", "old-b")
        )
        assertEquals(listOf("new-1", "new-2", "old-a", "old-b"), merged)
    }

    @Test
    fun keepsExistingWhenNothingNewArrived() {
        val existing = listOf("a", "b")
        assertEquals(existing, GeneratedLibraryMergePolicy.prependNewUrls(existing, listOf("b", "a")))
    }

    @Test
    fun shiftsFullscreenIndexSoTheSameImageStays() {
        assertEquals(3, GeneratedLibraryMergePolicy.shiftIndexAfterPrepend(1, 5, 2))
        assertEquals(0, GeneratedLibraryMergePolicy.shiftIndexAfterPrepend(0, 0, 2))
        assertEquals(1, GeneratedLibraryMergePolicy.shiftIndexAfterPrepend(1, 4, 0))
    }
}
