package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPendingBubbleTest {
    @Test
    fun dotsCycleOneToThree() {
        assertEquals("キャラ返信中.", ChatPendingBubble.text(0))
        assertEquals("キャラ返信中..", ChatPendingBubble.text(1))
        assertEquals("キャラ返信中...", ChatPendingBubble.text(2))
        assertEquals("キャラ返信中.", ChatPendingBubble.text(3))
    }

    @Test
    fun detectsPendingText() {
        assertTrue(ChatPendingBubble.isPending("キャラ返信中.."))
        assertTrue(ChatInterruptPolicy.isPendingPlaceholder("キャラ返信中..."))
        assertFalse(ChatPendingBubble.isPending("こんにちは"))
    }
}
