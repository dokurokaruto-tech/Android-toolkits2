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
    fun standaloneSizeModifierIsBoundToHorse() {
        val beforeNoun = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult("very huge, horse", ""),
            naturalLanguage = "大きい馬",
            existingNegative = "",
            useExisting = false
        )
        val afterNoun = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult("horse, very huge", ""),
            naturalLanguage = "大きい馬",
            existingNegative = "",
            useExisting = false
        )
        assertEquals("very huge horse", beforeNoun.mainPrompt)
        assertEquals("very huge horse", afterNoun.mainPrompt)
    }

    @Test
    fun eachModifierBindsOnlyToItsNearestSubject() {
        val result = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResult("very huge, horse, small, dog, running", ""),
            naturalLanguage = "大きい馬と小さい犬、犬は走っている",
            existingNegative = "",
            useExisting = false
        )
        assertEquals("very huge horse, small dog, running", result.mainPrompt)
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
