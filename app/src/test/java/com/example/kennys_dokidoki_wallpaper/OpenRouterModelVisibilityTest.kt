package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelVisibilityTest {
    @Test
    fun hidesBatchModels() {
        assertFalse(OpenRouterModelVisibility.isSelectable("openai/gpt-5:batch"))
        assertFalse(OpenRouterModelVisibility.isSelectable("anthropic/claude-sonnet-4:BATCH"))
    }

    @Test
    fun keepsRegularModels() {
        assertTrue(OpenRouterModelVisibility.isSelectable("openai/gpt-5"))
        assertTrue(OpenRouterModelVisibility.isSelectable("google/gemini-2.5-flash:free"))
        assertTrue(OpenRouterModelVisibility.isSelectable("vendor/batch-model"))
    }
}
