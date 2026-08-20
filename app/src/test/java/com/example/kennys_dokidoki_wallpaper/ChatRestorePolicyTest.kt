package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRestorePolicyTest {

    @Test
    fun `same image with in-memory chat keeps messages even if disk link is missing`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = false,
            resolvedChatId = null,
            currentChatId = "session-created",
            memoryHasMessages = true
        )
        assertEquals(ChatRestorePolicy.Action.RELINK_MEMORY, decision.action)
        assertEquals("session-created", decision.sessionId)
    }

    @Test
    fun `reopening a linked chat loads from disk when memory is empty`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = true,
            resolvedChatId = "session-abc",
            currentChatId = null,
            memoryHasMessages = false
        )
        assertEquals(ChatRestorePolicy.Action.LOAD_DISK, decision.action)
        assertEquals("session-abc", decision.sessionId)
    }

    @Test
    fun `wallpaper refresh of the same session does not wipe in-memory chat`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = false,
            resolvedChatId = "session-abc",
            currentChatId = "session-abc",
            memoryHasMessages = true
        )
        assertEquals(ChatRestorePolicy.Action.KEEP_MEMORY, decision.action)
        assertEquals("session-abc", decision.sessionId)
    }

    @Test
    fun `switching to a new character image starts a fresh chat`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = true,
            resolvedChatId = null,
            currentChatId = "old-session",
            memoryHasMessages = true
        )
        assertEquals(ChatRestorePolicy.Action.START_FRESH, decision.action)
        assertNull(decision.sessionId)
    }

    @Test
    fun `switching to another image with its own link loads that session`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = true,
            resolvedChatId = "other-session",
            currentChatId = "old-session",
            memoryHasMessages = true
        )
        assertEquals(ChatRestorePolicy.Action.LOAD_DISK, decision.action)
        assertEquals("other-session", decision.sessionId)
    }

    @Test
    fun `first open of an unlinked image starts fresh`() {
        val decision = ChatRestorePolicy.decide(
            imageChanged = true,
            resolvedChatId = null,
            currentChatId = null,
            memoryHasMessages = false
        )
        assertEquals(ChatRestorePolicy.Action.START_FRESH, decision.action)
    }
}
