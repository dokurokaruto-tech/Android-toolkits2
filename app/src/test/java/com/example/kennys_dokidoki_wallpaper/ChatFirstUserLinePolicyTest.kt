package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFirstUserLinePolicyTest {
    @Test
    fun requestUsesTheSavedSuggestTextAsTheUserVoiceGuidance() {
        val saved = "語尾は『かな』。\n例文：今日は会えてうれしいな。"

        val request = ChatFirstUserLinePolicy.buildRequest("放課後", saved)

        assertTrue(request.contains("プロフィール「放課後」"))
        assertTrue(request.contains(saved))
        assertTrue(request.contains("ユーザー側の最初の発言を1つだけ"))
        assertTrue(request.contains("キャラクターの返事は作らない"))
        assertTrue(request.contains("約40文字"))
    }

    @Test
    fun requestIncludesThePersonaSpecificTargetLength() {
        val request = ChatFirstUserLinePolicy.buildRequest("放課後", "", 72)

        assertTrue(request.contains("約72文字"))
    }

    @Test
    fun requestClampsTargetLengthToSupportedRange() {
        val request = ChatFirstUserLinePolicy.buildRequest("放課後", "", 900)

        assertTrue(request.contains("約500文字"))
    }

    @Test
    fun cleanGeneratedLineDropsSuggestionBlockAndOuterQuotes() {
        val result = ChatFirstUserLinePolicy.cleanGeneratedLine(
            "「今日は会えてうれしいな。」\n<<<SUGGESTIONS>>>\nA. また会いたい\n<<< /SUGGESTIONS >>>"
        )

        assertEquals("今日は会えてうれしいな。", result)
    }

    @Test
    fun blankGenerationIsRejected() {
        assertEquals(null, ChatFirstUserLinePolicy.cleanGeneratedLine("  \n  "))
    }
}
