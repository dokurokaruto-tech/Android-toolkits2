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

    @Test
    fun `snapshot keeps start-time tags even if later cards would change`() {
        val character = frozen("c1", "キャラ", "girl", setOf("金髪", "幼女"))
        val outfit = frozen("c2", "衣装", "dress", setOf("ワンピース"))
        val snapshot = GeneratedImageTagBinding.Snapshot(
            selected = listOf(character to 1, outfit to 2),
            roster = listOf(character, outfit),
            randomEnabledCategories = emptySet(),
            randomizerIncludedIds = emptySet(),
            width = 720,
            height = 1280,
            steps = 20,
            sampler = "Euler a",
            batchCount = 2
        )
        val prepared = GeneratedImageTagBinding.buildPreparedImages(snapshot, chance = { 100 }, pickIndex = { 0 })
        assertEquals(2, prepared.size)
        assertEquals(listOf("金髪", "幼女", "ワンピース"), prepared[0].tags)
        assertEquals(prepared[0].tags, prepared[1].tags)
        assertEquals(mapOf("c1" to 1, "c2" to 2), prepared[0].cardStates)
        assertEquals(prepared[0].cardStates, prepared[1].cardStates)
        assertTrue(prepared[0].prompt.contains("(dress:1.2)"))
    }

    @Test
    fun `randomizer cards chosen at start also contribute their auto tags`() {
        val base = frozen("c1", "キャラ", "girl", setOf("金髪"))
        val extra = frozen("c2", "衣装", "maid", setOf("メイド"), individual = true, probability = 80)
        val snapshot = GeneratedImageTagBinding.Snapshot(
            selected = listOf(base to 1),
            roster = listOf(base, extra),
            randomEnabledCategories = emptySet(),
            randomizerIncludedIds = emptySet(),
            width = 720,
            height = 1280,
            steps = 20,
            sampler = "Euler a",
            batchCount = 1
        )
        val included = GeneratedImageTagBinding.buildPreparedImages(snapshot, chance = { 10 }, pickIndex = { 0 })
        assertEquals(listOf("金髪", "メイド"), included.single().tags)
        val skipped = GeneratedImageTagBinding.buildPreparedImages(snapshot, chance = { 90 }, pickIndex = { 0 })
        assertEquals(listOf("金髪"), skipped.single().tags)
    }

    @Test
    fun `completed urls keep tags even if a middle task failed`() {
        val prepared = listOf(
            GeneratedImageTagBinding.PreparedImage("one", "", listOf("A")),
            GeneratedImageTagBinding.PreparedImage("two", "", listOf("B")),
            GeneratedImageTagBinding.PreparedImage("three", "", listOf("C"))
        )
        val matched = GeneratedImageTagBinding.tagsForCompletedUrls(
            listOf(
                "http://pc/api/v1/files/2026-08-22/GEN_stamp_abcd1234_0001.png",
                "http://pc/api/v1/files/2026-08-22/GEN_stamp_abcd1234_0003.png?token=x"
            ),
            prepared
        )
        assertEquals(listOf("A"), matched[0].second)
        assertEquals(listOf("C"), matched[1].second)
    }

    @Test
    fun `job tag lists survive encode and decode`() {
        val encoded = GeneratedImageTagBinding.encodeTagLists(
            listOf(listOf("金髪", "幼女"), emptyList(), listOf(" tail ", ""))
        )
        assertEquals(
            listOf(listOf("金髪", "幼女"), emptyList(), listOf("tail")),
            GeneratedImageTagBinding.decodeTagLists(encoded)
        )
        assertTrue(GeneratedImageTagBinding.decodeTagLists(null).isEmpty())
        assertEquals(listOf("金髪", "幼女"), GeneratedImageTagBinding.parseTagList(GeneratedImageTagBinding.encodeTagList(listOf("金髪", " 幼女 "))))
        assertEquals(
            listOf("a\"b", "c\\d"),
            GeneratedImageTagBinding.parseTagList(GeneratedImageTagBinding.encodeTagList(listOf("a\"b", "c\\d")))
        )
    }

    private fun frozen(
        id: String,
        category: String,
        prompt: String,
        tags: Set<String>,
        individual: Boolean = false,
        probability: Int = 50
    ) = GeneratedImageTagBinding.FrozenCard(
        id = id,
        category = category,
        mainPrompt = prompt,
        negativePrompt = "",
        appliedTags = tags,
        useIndividualRandomizer = individual,
        randomizerProbability = probability
    )
}
