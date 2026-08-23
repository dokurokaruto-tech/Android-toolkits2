package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCardInstructionPolicyTest {
    @Test
    fun storesAwayFromTagInstructionKeys() {
        assertTrue(PromptCardInstructionPolicy.usesIndependentKeysFromTags())
        assertNotEquals(TagInstructionPolicy.PROFILES_KEY, PromptCardInstructionPolicy.PROFILES_KEY)
        assertNotEquals(TagInstructionPolicy.LEGACY_KEY, PromptCardInstructionPolicy.LEGACY_KEY)
        assertNotEquals(TagInstructionPolicy.DEFAULT_PROMPT, PromptCardInstructionPolicy.DEFAULT_PROMPT)
    }

    @Test
    fun missingProfilesUseLegacyOrDefault() {
        val fromLegacy = PromptCardInstructionPolicy.parse(null, "カード用の指示")
        assertEquals(
            listOf(PromptCardInstructionPolicy.Profile("メイン", "カード用の指示")),
            fromLegacy
        )
        val fromEmpty = PromptCardInstructionPolicy.parse(null, "  ")
        assertEquals(PromptCardInstructionPolicy.DEFAULT_PROMPT, fromEmpty.single().content)
    }

    @Test
    fun encodeRoundTripsProfiles() {
        val source = listOf(
            PromptCardInstructionPolicy.Profile("短い", "名詞だけ"),
            PromptCardInstructionPolicy.Profile("詳しい", "色も書く")
        )
        val parsed = PromptCardInstructionPolicy.parse(PromptCardInstructionPolicy.encode(source), null)
        assertEquals(source, parsed)
    }

    @Test
    fun lastProfileCannotBeDeleted() {
        assertFalse(
            PromptCardInstructionPolicy.canDelete(
                listOf(PromptCardInstructionPolicy.Profile("メイン", "x"))
            )
        )
        assertTrue(
            PromptCardInstructionPolicy.canDelete(
                listOf(
                    PromptCardInstructionPolicy.Profile("A", "a"),
                    PromptCardInstructionPolicy.Profile("B", "b")
                )
            )
        )
    }
}
