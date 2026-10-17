package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BuilderCardSetHandoffTest {

    private val source = GeneratedImagePresetPolicy.Source(
        cardStates = mapOf("a" to 1),
        randomPickedIds = emptySet(),
        randomEnabledCategories = emptySet(),
        width = 720,
        height = 1280,
        steps = 20,
        sampler = "Euler a",
        thumbnail = "http://pc/x.png"
    )

    @Test
    fun lastOfferWinsAndConsumeClears() {
        assertNull(BuilderCardSetHandoff.consume())
        BuilderCardSetHandoff.offer(source)
        val newer = source.copy(cardStates = mapOf("b" to 2))
        BuilderCardSetHandoff.offer(newer)
        assertSame(newer, BuilderCardSetHandoff.consume())
        assertNull(BuilderCardSetHandoff.consume())
    }
}
