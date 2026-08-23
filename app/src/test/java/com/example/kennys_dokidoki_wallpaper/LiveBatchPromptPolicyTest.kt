package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBatchPromptPolicyTest {
    @Test
    fun fingerprintChangesWhenRandomizerToggles() {
        val base = snapshot(randomCats = emptySet())
        val on = snapshot(randomCats = setOf("髪"))
        assertNotEquals(
            LiveBatchPromptPolicy.fingerprint(base),
            LiveBatchPromptPolicy.fingerprint(on)
        )
    }

    @Test
    fun fingerprintChangesWhenSelectedCardsChange() {
        val one = snapshot(selected = listOf(frozen("c1") to 1))
        val two = snapshot(selected = listOf(frozen("c1") to 1, frozen("c2") to 2))
        assertNotEquals(
            LiveBatchPromptPolicy.fingerprint(one),
            LiveBatchPromptPolicy.fingerprint(two)
        )
    }

    @Test
    fun pendingCountIgnoresFinishedSlots() {
        assertEquals(4, LiveBatchPromptPolicy.pendingCount(total = 5, completed = 1, failed = 0))
        assertEquals(0, LiveBatchPromptPolicy.pendingCount(total = 3, completed = 2, failed = 1))
        assertEquals(0, LiveBatchPromptPolicy.pendingCount(total = 1, completed = 3, failed = 0))
    }

    @Test
    fun spliceKeepsFinishedImagesAndReplacesTheTail() {
        val existing = listOf(prepared("a"), prepared("b"), prepared("c"), prepared("d"))
        val spliced = LiveBatchPromptPolicy.splicePrepared(
            existing,
            pendingStart = 2,
            replacement = listOf(prepared("x"), prepared("y"))
        )
        assertEquals(listOf("a", "b", "x", "y"), spliced.map { it.prompt })
    }

    @Test
    fun spliceKeepsUnupdatedPendingWhenReplacementIsShorter() {
        val existing = listOf(prepared("a"), prepared("b"), prepared("c"), prepared("d"))
        val spliced = LiveBatchPromptPolicy.splicePrepared(
            existing,
            pendingStart = 1,
            replacement = listOf(prepared("x"))
        )
        assertEquals(listOf("a", "x", "c", "d"), spliced.map { it.prompt })
    }

    @Test
    fun pendingStartIsTheFirstUnstartedSlot() {
        assertEquals(2, LiveBatchPromptPolicy.pendingStart(total = 5, pending = 3))
        assertEquals(0, LiveBatchPromptPolicy.pendingStart(total = 4, pending = 4))
    }

    @Test
    fun refreshIsSkippedWhenNothingPendingOrUnchanged() {
        assertFalse(LiveBatchPromptPolicy.shouldSendRefresh("same", "same", 3))
        assertFalse(LiveBatchPromptPolicy.shouldSendRefresh(null, "next", 0))
        assertTrue(LiveBatchPromptPolicy.shouldSendRefresh("old", "next", 2))
    }

    private fun frozen(id: String) = GeneratedImageTagBinding.FrozenCard(
        id = id,
        category = "髪",
        mainPrompt = id,
        negativePrompt = "",
        appliedTags = emptySet(),
        useIndividualRandomizer = false,
        randomizerProbability = 50
    )

    private fun snapshot(
        selected: List<Pair<GeneratedImageTagBinding.FrozenCard, Int>> = listOf(frozen("c1") to 1),
        randomCats: Set<String> = emptySet()
    ) = GeneratedImageTagBinding.Snapshot(
        selected = selected,
        roster = selected.map { it.first },
        randomEnabledCategories = randomCats,
        randomizerIncludedIds = emptySet(),
        width = 720,
        height = 1280,
        steps = 20,
        sampler = "Euler a",
        batchCount = 1
    )

    private fun prepared(prompt: String) = GeneratedImageTagBinding.PreparedImage(
        prompt = prompt,
        negativePrompt = "",
        tags = emptyList()
    )
}
