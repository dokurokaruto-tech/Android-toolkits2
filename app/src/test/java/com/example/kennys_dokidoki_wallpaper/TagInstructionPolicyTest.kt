package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagInstructionPolicyTest {
    @Test
    fun missingProfilesUseLegacyOrDefault() {
        val fromLegacy = TagInstructionPolicy.parse(null, "古い指示")
        assertEquals(listOf(TagInstructionPolicy.Profile("メイン", "古い指示")), fromLegacy)

        val fromEmpty = TagInstructionPolicy.parse(null, "  ")
        assertEquals(1, fromEmpty.size)
        assertEquals("メイン", fromEmpty.first().name)
        assertEquals(TagInstructionPolicy.DEFAULT_PROMPT, fromEmpty.first().content)
    }

    @Test
    fun encodeRoundTripsProfiles() {
        val source = listOf(
            TagInstructionPolicy.Profile("人物", "人物だけ書く"),
            TagInstructionPolicy.Profile("服", "服装だけ書く")
        )
        val parsed = TagInstructionPolicy.parse(TagInstructionPolicy.encode(source), null)
        assertEquals(source, parsed)
    }

    @Test
    fun lastProfileCannotBeDeleted() {
        assertFalse(TagInstructionPolicy.canDelete(listOf(TagInstructionPolicy.Profile("メイン", "x"))))
        assertTrue(
            TagInstructionPolicy.canDelete(
                listOf(
                    TagInstructionPolicy.Profile("A", "a"),
                    TagInstructionPolicy.Profile("B", "b")
                )
            )
        )
    }

    @Test
    fun emptyListFallsBackToDefault() {
        val ensured = TagInstructionPolicy.ensureNonEmpty(emptyList())
        assertEquals(1, ensured.size)
        assertEquals(TagInstructionPolicy.DEFAULT_PROMPT, ensured.first().content)
    }

    @Test
    fun removeKeepsAtLeastOnePreset() {
        val two = listOf(
            TagInstructionPolicy.Profile("A", "a"),
            TagInstructionPolicy.Profile("B", "b")
        )
        assertEquals(listOf(TagInstructionPolicy.Profile("B", "b")), TagInstructionPolicy.removeAt(two, 0))
        assertEquals(two, TagInstructionPolicy.removeAt(listOf(TagInstructionPolicy.Profile("A", "a")), 0))
    }

    @Test
    fun selectedIndexFallsBackToFirst() {
        val list = listOf(
            TagInstructionPolicy.Profile("A", "a"),
            TagInstructionPolicy.Profile("B", "b")
        )
        assertEquals(1, TagInstructionPolicy.selectedIndex(list, "B"))
        assertEquals(0, TagInstructionPolicy.selectedIndex(list, "missing"))
    }
}
