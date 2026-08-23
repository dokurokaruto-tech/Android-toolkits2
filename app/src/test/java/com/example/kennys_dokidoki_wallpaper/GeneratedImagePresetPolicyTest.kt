package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedImagePresetPolicyTest {
    @Test
    fun storedCardStatesWinOverTagInference() {
        val source = GeneratedImagePresetPolicy.sourceFrom(
            storedCards = mapOf("card-a" to 2),
            imageTags = setOf("金髪"),
            roster = listOf(GeneratedImagePresetPolicy.InferableCard("card-b", setOf("金髪"))),
            width = 1280,
            height = 720,
            steps = 30,
            sampler = "DPM++ 2M",
            thumbnail = "http://pc/a.png"
        )!!
        assertEquals(mapOf("card-a" to 2), source.cardStates)
        assertEquals("1280 x 720", PresetSavePolicy.defaultName(source.width, source.height))
        assertEquals(PresetSavePolicy.QUICK_CATEGORY, PresetSavePolicy.defaultCategory())
        assertEquals("http://pc/a.png", source.thumbnail)
        assertEquals(30, source.steps)
    }

    @Test
    fun infersCardsWhoseTagsAreAllOnTheImage() {
        val inferred = GeneratedImagePresetPolicy.inferCardStates(
            imageTags = setOf("金髪", "幼女", " tail "),
            roster = listOf(
                GeneratedImagePresetPolicy.InferableCard("hair", setOf("金髪")),
                GeneratedImagePresetPolicy.InferableCard("age", setOf("幼女")),
                GeneratedImagePresetPolicy.InferableCard("extra", setOf(" tail ", "帽子"))
            )
        )
        assertEquals(mapOf("hair" to 1, "age" to 1), inferred)
    }

    @Test
    fun missingSourceReturnsNull() {
        assertNull(
            GeneratedImagePresetPolicy.sourceFrom(
                storedCards = emptyMap(),
                imageTags = emptySet(),
                roster = emptyList(),
                width = null,
                height = null,
                steps = null,
                sampler = null,
                thumbnail = "http://pc/a.png"
            )
        )
    }
}
