package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageTagBindingTest {

    @Test
    fun `unions tags from every card that went into the image`() {
        val tags = GeneratedImageTagBinding.collect(
            listOf(
                setOf("金髪", "幼女"),
                setOf("幼女", " tail "),
                emptySet(),
                listOf("")
            )
        )
        assertEquals(setOf("金髪", "幼女", "tail"), tags)
    }

    @Test
    fun `browse keeps a user edited draft over generated tags`() {
        val merged = GeneratedImageTagBinding.mergeForBrowse(
            existingDraftTags = setOf("編集済"),
            generatedTags = setOf("金髪", "幼女")
        )
        assertEquals(setOf("編集済"), merged)
    }

    @Test
    fun `browse uses generated tags when the draft is still empty`() {
        val merged = GeneratedImageTagBinding.mergeForBrowse(
            existingDraftTags = emptySet(),
            generatedTags = setOf("金髪", "幼女")
        )
        assertEquals(setOf("金髪", "幼女"), merged)
        assertTrue(GeneratedImageTagBinding.mergeForBrowse(emptySet(), emptySet()).isEmpty())
    }
}
