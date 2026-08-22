package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedImageLifecycleTest {

    @Test
    fun `importing a generated image migrates tags description and chat`() {
        val plan = GeneratedImageLifecycle.importDraft(
            destKey = "content://local/imported.png",
            draft = GeneratedImageLifecycle.Draft(
                tags = setOf("金髪", "幼女"),
                description = "忍",
                linkedChatId = "chat-1"
            ),
            sessionName = "⏳ #金髪 #幼女"
        )
        assertEquals("content://local/imported.png", plan.destKey)
        assertEquals(setOf("金髪", "幼女"), plan.tags)
        assertEquals("忍", plan.description)
        assertEquals("chat-1", plan.chatId)
        assertEquals("#金髪 #幼女", plan.persistedSessionName)
    }

    @Test
    fun `deleting the only image that owns a chat also deletes that chat`() {
        val plan = GeneratedImageLifecycle.deletePlan(
            imageKey = "generated:2026-08-22/a.png",
            linkedChatId = "chat-1",
            otherKeysUsingChat = listOf("generated:2026-08-22/a.png")
        )
        assertEquals("chat-1", plan.chatIdToDelete)
        assertEquals(listOf("generated:2026-08-22/a.png"), plan.unlinkKeys)
    }

    @Test
    fun `shared chats stay when another image still uses them`() {
        val plan = GeneratedImageLifecycle.deletePlan(
            imageKey = "generated:2026-08-22/a.png",
            linkedChatId = "chat-1",
            otherKeysUsingChat = listOf("content://local/imported.png")
        )
        assertNull(plan.chatIdToDelete)
    }

    @Test
    fun `images without a chat only unlink themselves`() {
        val plan = GeneratedImageLifecycle.deletePlan(
            imageKey = "generated:2026-08-22/a.png",
            linkedChatId = null,
            otherKeysUsingChat = emptyList()
        )
        assertNull(plan.chatIdToDelete)
    }
}
