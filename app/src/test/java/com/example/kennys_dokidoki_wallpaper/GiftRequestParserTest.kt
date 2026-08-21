package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftRequestParserTest {

    @Test
    fun `closed gift request is stripped and only the first id is kept`() {
        val raw = """
            ねえ、ランジェリーほしいな。
            <<<GIFT_REQUEST>>>
            ID: cherry
            ID: cake
            <<</GIFT_REQUEST>>>
        """.trimIndent()
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("ねえ、ランジェリーほしいな。", parsed.cleanText)
        assertEquals(listOf("cherry"), parsed.catalogIds)
        assertTrue(parsed.hasRequests)
    }

    @Test
    fun `unclosed tag still hides the tail`() {
        val raw = "本文\n<<<GIFT_REQUEST>>>\nID: bouquet"
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("本文", parsed.cleanText)
        assertEquals(listOf("bouquet"), parsed.catalogIds)
    }

    @Test
    fun `name and emoji lines resolve to catalog ids but keep one`() {
        val ids = GiftRequestParser.parseBlock("🖤 ランジェリー\nヴィンテージシャンパン\nallowance")
        assertEquals(listOf("cherry"), ids)
    }

    @Test
    fun `unknown lines are ignored and duplicates collapse`() {
        val ids = GiftRequestParser.parseBlock("ID: cherry\nID: cherry\nID: spaceship")
        assertEquals(listOf("cherry"), ids)
    }

    @Test
    fun `failed parse still strips the flag from the bubble`() {
        val raw = "セリフ\n<<<GIFT_REQUEST>>>\nID: not-a-real-gift\n<<</GIFT_REQUEST>>>"
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("セリフ", parsed.cleanText)
        assertFalse(parsed.hasRequests)
    }

    @Test
    fun `applyTo writes ids onto the node and removes the flag`() {
        val node = ChatNode(
            text = "ほしい。\n<<<GIFT_REQUEST>>>\nID: ring\n<<</GIFT_REQUEST>>>",
            isUser = false
        )
        GiftRequestParser.applyTo(node)
        assertEquals("ほしい。", node.text)
        assertEquals(listOf("ring"), node.giftRequestIds)
    }

    @Test
    fun `streaming visibleText hides gift request like suggestions`() {
        val raw = "途中\n<<<GIFT_REQUEST>>>\nID: ch"
        assertEquals("途中", ChatSuggestionParser.visibleText(raw))
    }

    @Test
    fun `ignored turns grow only while the list remains`() {
        assertEquals(0, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = false, currentIgnored = 4))
        assertEquals(1, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = true, currentIgnored = 0))
        assertEquals(3, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = true, currentIgnored = 2))
    }

    @Test
    fun `mood block names the pending gifts and stays quiet when empty`() {
        assertEquals("", GiftMoodPolicy.moodBlock(emptyList(), 0))
        val block = GiftMoodPolicy.moodBlock(
            listOf(GiftWish("cherry", "ランジェリー", "🖤")),
            ignoredTurns = 3
        )
        assertTrue(block.contains("ランジェリー"))
        assertTrue(block.contains("不機嫌"))
        assertFalse(block.contains("GIFT-"))
    }

    @Test
    fun `request instructions ask for one catalog item and never mention the internal code prefix`() {
        val block = GiftMoodPolicy.requestInstructionBlock()
        assertTrue(block.contains("<<<GIFT_REQUEST>>>"))
        assertTrue(block.contains("1個") || block.contains("1行"))
        assertFalse(block.contains("1〜3"))
        assertFalse(block.contains("GIFT-"))
        assertFalse(block.contains("HMAC"))
        GiftStore.catalog.forEach { item ->
            if (item.amountYen != null) {
                assertTrue(item.amountYen >= 10_000)
            } else {
                assertTrue(item.minYen >= 10_000)
            }
        }
    }
}
