package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptCardPromptMinimalizerTest {
    @Test
    fun horseRemovesInventedMorningAndQualityBoilerplate() {
        val result = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult(
                mainPrompt = "horse, standing in a field, morning, masterpiece, 8k, highly detailed",
                negativePrompt = "low quality, blurry"
            ),
            naturalLanguage = "馬",
            existingNegative = "",
            useExisting = false
        )
        assertEquals("horse", result.mainPrompt)
        assertEquals("", result.negativePrompt)
    }

    @Test
    fun explicitlyRequestedTimeIsKept() {
        val result = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult("horse, morning", ""),
            naturalLanguage = "朝の馬",
            existingNegative = "",
            useExisting = false
        )
        assertEquals("horse, morning", result.mainPrompt)
    }

    @Test
    fun explicitExclusionAllowsNegativePrompt() {
        val result = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult("horse", "people"),
            naturalLanguage = "馬、人は入れない",
            existingNegative = "",
            useExisting = false
        )
        assertEquals("people", result.negativePrompt)
    }
}
