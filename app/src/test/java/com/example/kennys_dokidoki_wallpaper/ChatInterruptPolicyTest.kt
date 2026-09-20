package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInterruptPolicyTest {
    @Test
    fun `action becomes stop only when this session generates`() {
        assertEquals(ChatInterruptPolicy.SendAction.STOP,
            ChatInterruptPolicy.actionFor(isGenerating = true, activeSessionId = "a", currentSessionId = "a"))
        assertEquals(ChatInterruptPolicy.SendAction.SEND,
            ChatInterruptPolicy.actionFor(isGenerating = false, activeSessionId = "a", currentSessionId = "a"))
        assertEquals(ChatInterruptPolicy.SendAction.SEND,
            ChatInterruptPolicy.actionFor(isGenerating = true, activeSessionId = "b", currentSessionId = "a"))
        assertEquals(ChatInterruptPolicy.SendAction.SEND,
            ChatInterruptPolicy.actionFor(isGenerating = true, activeSessionId = null, currentSessionId = "a"))
    }

    @Test
    fun `placeholder detection covers animations and blanks`() {
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder(""))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("   "))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("思考中..."))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("推論中..."))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("🧠 推論中 (Local)."))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("📥 モデルをロードしています..."))
        assertFalse(ChatInterruptPolicy.isPendingPlaceholder("こんにちは"))
    }

    @Test
    fun `interrupt keeps partial text and marks empty`() {
        assertEquals("こんにちは", ChatInterruptPolicy.interruptedBubbleText("こんにちは"))
        assertEquals(ChatInterruptPolicy.INTERRUPTED_TEXT, ChatInterruptPolicy.interruptedBubbleText("推論中..."))
        assertEquals(ChatInterruptPolicy.INTERRUPTED_TEXT, ChatInterruptPolicy.interruptedBubbleText(""))
    }
}
