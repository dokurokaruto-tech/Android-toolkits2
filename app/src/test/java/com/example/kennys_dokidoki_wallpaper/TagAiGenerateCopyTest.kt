package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TagAiGenerateCopyTest {
    @Test
    fun modelLineDropsEnglishPrefix() {
        assertEquals("xAI · grok-4-1-fast-non-reasoning", TagAiGenerateCopy.modelLine("GROK", "grok-4-1-fast-non-reasoning"))
        assertEquals("OpenRouter · deepseek/v4", TagAiGenerateCopy.modelLine("OPENROUTER", "deepseek/v4"))
    }

    @Test
    fun usageLineUsesRemainingCount() {
        assertEquals("残り 12 回", TagAiGenerateCopy.usageLine(38, 50))
        assertEquals("残り 0 回", TagAiGenerateCopy.usageLine(80, 50))
    }

    @Test
    fun copyUsesPresetNotProfile() {
        assertEquals("指示プリセット", TagAiGenerateCopy.PRESET_LABEL)
        assertFalse(TagAiGenerateCopy.PRESET_LABEL.contains("プロファイル"))
        assertFalse(TagAiGenerateCopy.TITLE.contains("AI生成"))
        assertFalse(TagAiGenerateCopy.GENERATE.contains("錬成"))
    }
}