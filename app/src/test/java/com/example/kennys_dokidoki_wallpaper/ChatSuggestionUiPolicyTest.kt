package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionUiPolicyTest {
    @Test
    fun readyWinsOnceAnyChoiceExists() {
        val phase = ChatSuggestionUiPolicy.phase(
            enabled = true,
            isLastMessage = true,
            isUser = false,
            generatingThis = true,
            rawText = "返事\n<<<SUGGESTIONS>>>\nA: はい",
            suggestionA = "はい",
            suggestionB = null,
            suggestionC = null
        )
        assertEquals(ChatSuggestionUiPolicy.Phase.READY, phase)
    }

    @Test
    fun generatingOnlyAfterTheHiddenMarker() {
        val bodyOnly = ChatSuggestionUiPolicy.phase(
            enabled = true,
            isLastMessage = true,
            isUser = false,
            generatingThis = true,
            rawText = "まだ本文だけ",
            suggestionA = null,
            suggestionB = null,
            suggestionC = null
        )
        assertEquals(ChatSuggestionUiPolicy.Phase.HIDDEN, bodyOnly)

        val afterMarker = ChatSuggestionUiPolicy.phase(
            enabled = true,
            isLastMessage = true,
            isUser = false,
            generatingThis = true,
            rawText = "本文\n<<<SUGGESTIONS>>>\nA: いま",
            suggestionA = null,
            suggestionB = null,
            suggestionC = null
        )
        assertEquals(ChatSuggestionUiPolicy.Phase.GENERATING, afterMarker)
    }

    @Test
    fun olderOrUserRowsStayHidden() {
        val older = ChatSuggestionUiPolicy.phase(
            enabled = true,
            isLastMessage = false,
            isUser = false,
            generatingThis = false,
            rawText = "前の返事\n<<<SUGGESTIONS>>>\nA: あ",
            suggestionA = null,
            suggestionB = null,
            suggestionC = null
        )
        assertEquals(ChatSuggestionUiPolicy.Phase.HIDDEN, older)
        val user = ChatSuggestionUiPolicy.phase(
            enabled = true,
            isLastMessage = true,
            isUser = true,
            generatingThis = false,
            rawText = "こんにちは",
            suggestionA = null,
            suggestionB = null,
            suggestionC = null
        )
        assertEquals(ChatSuggestionUiPolicy.Phase.HIDDEN, user)
    }

    @Test
    fun toastOnlyWhenEnabledCompleteAndEmpty() {
        assertTrue(
            ChatSuggestionUiPolicy.shouldToastFailure(
                enabled = true,
                isComplete = true,
                isError = false,
                suggestionA = null,
                suggestionB = null,
                suggestionC = null
            )
        )
        assertFalse(
            ChatSuggestionUiPolicy.shouldToastFailure(
                enabled = true,
                isComplete = true,
                isError = false,
                suggestionA = "はい",
                suggestionB = null,
                suggestionC = null
            )
        )
        assertFalse(
            ChatSuggestionUiPolicy.shouldToastFailure(
                enabled = true,
                isComplete = true,
                isError = true,
                suggestionA = null,
                suggestionB = null,
                suggestionC = null
            )
        )
        assertFalse(
            ChatSuggestionUiPolicy.shouldToastFailure(
                enabled = false,
                isComplete = true,
                isError = false,
                suggestionA = null,
                suggestionB = null,
                suggestionC = null
            )
        )
    }
}
