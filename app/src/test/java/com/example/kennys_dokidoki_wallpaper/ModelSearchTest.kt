package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSearchTest {
    @Test
    fun matchesFromFirstCharacter() {
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", "g"))
        assertFalse(ModelSearch.matches("Gemini", "google/gemini", "x"))
    }

    @Test
    fun searchesNameAndIdIgnoringCase() {
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", "GEM"))
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", "GOOGLE/"))
    }

    @Test
    fun emptyQueryRestoresAllModels() {
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", ""))
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", "  "))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertTrue(ModelSearch.matches("Gemini", "google/gemini", "  gemini  "))
        assertFalse(ModelSearch.matches("Gemini", "google/gemini", "grok"))
    }
}
