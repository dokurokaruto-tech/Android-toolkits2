package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftRequestParserTest {

    @Test
    fun `closed allowance request is stripped and yen is read`() {
        val raw = """
            ねえ、1万5千円ほしいな。
            <<<ALLOWANCE_REQUEST>>>
            YEN: 15000
            <<</ALLOWANCE_REQUEST>>>
        """.trimIndent()
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("ねえ、1万5千円ほしいな。", parsed.cleanText)
        assertEquals(15000, parsed.amountYen)
        assertTrue(parsed.hasRequests)
    }

    @Test
    fun `legacy gift request tag still parses yen`() {
        val raw = "本文\n<<<GIFT_REQUEST>>>\nYEN: 20000"
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("本文", parsed.cleanText)
        assertEquals(20000, parsed.amountYen)
    }

    @Test
    fun `comma and yen marks are accepted`() {
        assertEquals(32000, GiftRequestParser.parseYenLine("￥32,000円"))
        assertEquals(10000, GiftRequestParser.parseYenLine("金額: 10000"))
    }

    @Test
    fun `below minimum or junk is ignored`() {
        assertNull(GiftRequestParser.parseYenLine("YEN: 500"))
        assertNull(GiftRequestParser.parseYenLine("ランジェリー"))
        assertNull(GiftRequestParser.parseBlock("ID: cherry\nID: cake"))
    }

    @Test
    fun `failed parse still strips the flag from the bubble`() {
        val raw = "セリフ\n<<<ALLOWANCE_REQUEST>>>\nYEN: 12\n<<</ALLOWANCE_REQUEST>>>"
        val parsed = GiftRequestParser.parse(raw)
        assertEquals("セリフ", parsed.cleanText)
        assertFalse(parsed.hasRequests)
    }

    @Test
    fun `applyTo writes yen onto the node and removes the flag`() {
        val node = ChatNode(
            text = "ほしい。\n<<<ALLOWANCE_REQUEST>>>\nYEN: 88000\n<<</ALLOWANCE_REQUEST>>>",
            isUser = false
        )
        GiftRequestParser.applyTo(node)
        assertEquals("ほしい。", node.text)
        assertEquals(88000, node.giftRequestYen)
    }

    @Test
    fun `streaming visibleText hides allowance request like suggestions`() {
        val raw = "途中\n<<<ALLOWANCE_REQUEST>>>\nYEN: 1"
        assertEquals("途中", ChatSuggestionParser.visibleText(raw))
        assertEquals("途中", ChatSuggestionParser.visibleText("途中\n<<<GIFT_REQUEST>>>\nYEN: 1"))
    }

    @Test
    fun `ignored turns grow only while the list remains`() {
        assertEquals(0, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = false, currentIgnored = 4))
        assertEquals(1, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = true, currentIgnored = 0))
        assertEquals(3, GiftMoodPolicy.nextIgnoredTurns(pendingRemaining = true, currentIgnored = 2))
    }

    @Test
    fun `mood block names the pending yen and stays quiet when empty`() {
        assertEquals("", GiftMoodPolicy.moodBlock(emptyList(), 0))
        val block = GiftMoodPolicy.moodBlock(
            listOf(GiftWish(amountYen = 15000)),
            ignoredTurns = 3
        )
        assertTrue(block.contains("お小遣い"))
        assertTrue(block.contains("15,000") || block.contains("15000"))
        assertTrue(block.contains("不機嫌"))
        assertFalse(block.contains("GIFT-"))
    }

    @Test
    fun `request instructions ask for cash only`() {
        val block = GiftMoodPolicy.requestInstructionBlock()
        assertTrue(block.contains("<<<ALLOWANCE_REQUEST>>>"))
        assertTrue(block.contains("YEN:"))
        assertFalse(block.contains("ランジェリー"))
        assertFalse(block.contains("GIFT-"))
        assertFalse(block.contains("HMAC"))
        assertEquals(1, GiftStore.catalog.size)
        assertEquals(GiftStore.ALLOWANCE_ID, GiftStore.catalog.single().id)
        assertTrue(GiftStore.catalog.single().minYen >= 10_000)
    }
}
