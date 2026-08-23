package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPipPresenceTest {
    @Test
    fun notifiesWhenPipCloses() {
        GenerationPipPresence.setActive(false)
        val seen = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { seen.add(it) }
        GenerationPipPresence.addListener(listener)
        try {
            GenerationPipPresence.setActive(true)
            GenerationPipPresence.setActive(true)
            GenerationPipPresence.setActive(false)
            assertEquals(listOf(true, false), seen)
            assertFalse(GenerationPipPresence.isActive)
        } finally {
            GenerationPipPresence.removeListener(listener)
            GenerationPipPresence.setActive(false)
        }
        assertTrue(seen.size == 2)
    }
}
