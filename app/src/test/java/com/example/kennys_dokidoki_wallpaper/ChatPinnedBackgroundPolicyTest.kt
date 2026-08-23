package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPinnedBackgroundPolicyTest {
    @Test
    fun pinnedChatCoversTheLiveWallpaper() {
        assertTrue(ChatPinnedBackgroundPolicy.shouldCoverLiveWallpaper("http://pc/file.png"))
        assertFalse(ChatPinnedBackgroundPolicy.shouldCoverLiveWallpaper(null))
        assertFalse(ChatPinnedBackgroundPolicy.shouldCoverLiveWallpaper("  "))
    }

    @Test
    fun cachedOriginalIsPreferredOverTheRemoteUri() {
        val bytes = byteArrayOf(1, 2, 3)
        assertSame(bytes, ChatPinnedBackgroundPolicy.loadModel(bytes, "http://pc/file.png"))
        assertEquals("http://pc/file.png", ChatPinnedBackgroundPolicy.loadModel(null, "http://pc/file.png"))
    }

    @Test
    fun thumbnailPrefersTheLaunchExtra() {
        assertEquals(
            "thumb://a",
            ChatPinnedBackgroundPolicy.thumbnailUri(" thumb://a ", "thumb://b")
        )
        assertEquals("thumb://b", ChatPinnedBackgroundPolicy.thumbnailUri(null, "thumb://b"))
        assertEquals(null, ChatPinnedBackgroundPolicy.thumbnailUri("  ", " "))
    }
}
