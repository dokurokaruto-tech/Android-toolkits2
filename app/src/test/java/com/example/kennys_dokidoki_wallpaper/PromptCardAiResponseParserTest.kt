package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCardAiResponseParserTest {
    @Test
    fun parsesJsonInsideMarkdownFence() {
        val result = PromptCardAiResponseParser.parse(
            """```json
                {"main_prompt":"1girl, black hair, sunset","negative_prompt":"blurry, low quality"}
                ```""".trimIndent()
        )
        assertEquals("1girl, black hair, sunset", result.mainPrompt)
        assertEquals("blurry, low quality", result.negativePrompt)
    }

    @Test
    fun parsesSmallLocalModelFallback() {
        val result = PromptCardAiResponseParser.parse(
            "MAIN: 1boy, white shirt, city\nNEGATIVE: bad hands, text"
        )
        assertEquals("1boy, white shirt, city", result.mainPrompt)
        assertEquals("bad hands, text", result.negativePrompt)
    }

    @Test
    fun existingPromptIsIncludedOnlyWhenRequested() {
        val withoutExisting = PromptCardAiGenerator.buildUserPrompt(
            "海辺を走る少女", "old main", "old negative", false
        )
        val withExisting = PromptCardAiGenerator.buildUserPrompt(
            "海辺を走る少女", "old main", "old negative", true
        )
        assertFalse(withoutExisting.contains("old main"))
        assertTrue(withExisting.contains("old main"))
        assertTrue(withExisting.contains("old negative"))
    }
}
