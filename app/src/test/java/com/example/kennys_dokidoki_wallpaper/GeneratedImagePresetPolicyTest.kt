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
    fun promptRestoresEverySelectedCardAndItsLevel() {
        val roster = listOf(
            GeneratedImagePresetPolicy.InferableCard("hair", emptySet(), "blonde hair"),
            GeneratedImagePresetPolicy.InferableCard("pose", emptySet(), "sitting"),
            GeneratedImagePresetPolicy.InferableCard("look", emptySet(), "smile"),
            GeneratedImagePresetPolicy.InferableCard("unused", emptySet(), "standing")
        )
        val prompt = "blonde hair, (sitting:1.2), (smile:1.6)"
        val inferred = GeneratedImagePresetPolicy.inferCardStatesFromPrompt(prompt, roster)
        assertEquals(mapOf("hair" to 1, "pose" to 2, "look" to 3), inferred)
        val source = GeneratedImagePresetPolicy.sourceFrom(
            storedCards = emptyMap(),
            imageTags = setOf("金髪"),
            roster = roster,
            width = 720,
            height = 1280,
            steps = 20,
            sampler = "Euler a",
            thumbnail = "http://pc/a.png",
            prompt = prompt
        )!!
        assertEquals(3, source.cardStates.size)
        assertEquals(2, source.cardStates["pose"])
    }

    @Test
    fun longerPromptIsMatchedBeforeItsSubstring() {
        val inferred = GeneratedImagePresetPolicy.inferCardStatesFromPrompt(
            "very long blonde hair, blonde hair",
            listOf(
                GeneratedImagePresetPolicy.InferableCard("short", emptySet(), "blonde hair"),
                GeneratedImagePresetPolicy.InferableCard("long", emptySet(), "very long blonde hair")
            )
        )
        assertEquals(mapOf("long" to 1, "short" to 1), inferred)
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
    fun randomizerModeDropsPickedCardsAndKeepsCategories() {
        val source = GeneratedImagePresetPolicy.sourceFrom(
            storedCards = mapOf("fixed" to 2, "rolled" to 1),
            imageTags = emptySet(),
            roster = emptyList(),
            width = 720,
            height = 1280,
            steps = 20,
            sampler = "Euler a",
            thumbnail = "http://pc/a.png",
            randomPickedIds = setOf("rolled"),
            randomEnabledCategories = setOf("髪")
        )!!
        assertEquals(
            mapOf("fixed" to 2, "rolled" to 1),
            GeneratedImagePresetPolicy.cardsForMode(source, GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
        )
        assertEquals(
            mapOf("fixed" to 2),
            GeneratedImagePresetPolicy.cardsForMode(source, GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER)
        )
        assertEquals(
            setOf("髪"),
            GeneratedImagePresetPolicy.randomCategoriesForMode(source, GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER)
        )
        assertEquals(
            emptySet<String>(),
            GeneratedImagePresetPolicy.randomCategoriesForMode(source, GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
        )
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
