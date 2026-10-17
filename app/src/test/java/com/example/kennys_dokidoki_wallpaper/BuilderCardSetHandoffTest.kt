package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
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
        BuilderCardSetHandoff.offer(
            BuilderCardSetHandoff.Request(
                source,
                GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
            )
        )
        val mode = GeneratedImagePresetPolicy.FromImageMode.KEEP_RANDOMIZER
        val newer = BuilderCardSetHandoff.Request(source.copy(cardStates = mapOf("b" to 2)), mode)
        BuilderCardSetHandoff.offer(newer)
        val consumed = BuilderCardSetHandoff.consume()!!
        assertSame(newer.source, consumed.source)
        assertEquals(mode, consumed.mode)
        assertNull(BuilderCardSetHandoff.consume())
    }
}
