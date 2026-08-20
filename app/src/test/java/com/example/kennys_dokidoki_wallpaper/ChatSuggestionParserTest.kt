package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionParserTest {

    @Test
    fun `closed triple-angle block is stripped and split into abc`() {
        val raw = """
            こんにちは、 Kenny。
            <<<SUGGESTIONS>>>
            A: おはよう
            B: 元気？
            C: だきしめて
            <<</SUGGESTIONS>>>
        """.trimIndent()
        val parsed = ChatSuggestionParser.parse(raw)
        assertEquals("こんにちは、 Kenny。", parsed.cleanText)
        assertEquals("おはよう", parsed.suggestionA)
        assertEquals("元気？", parsed.suggestionB)
        assertEquals("だきしめて", parsed.suggestionC)
        assertTrue(parsed.hasSuggestions)
    }

    @Test
    fun `unclosed tag still hides the tail and extracts choices`() {
        val raw = "本文です\n<<<SUGGESTIONS>>>\nA: はい\nB: いいえ\nC: あとで"
        val parsed = ChatSuggestionParser.parse(raw)
        assertEquals("本文です", parsed.cleanText)
        assertEquals("はい", parsed.suggestionA)
        assertEquals("いいえ", parsed.suggestionB)
        assertEquals("あとで", parsed.suggestionC)
    }

    @Test
    fun `visibleText hides from the marker even while the block is incomplete`() {
        val raw = "途中の返事\n<<<SUGGESTIONS>>>\nA: いま"
        assertEquals("途中の返事", ChatSuggestionParser.visibleText(raw))
        assertEquals("タグなし", ChatSuggestionParser.visibleText("タグなし"))
    }

    @Test
    fun `fullwidth colon and spaced tags still parse`() {
        val raw = """
            返事
            <<< SUGGESTIONS >>>
            A：にゃーん
            B：ふみゅ
            C：ぱないの
            <<< /SUGGESTIONS >>>
        """.trimIndent()
        val parsed = ChatSuggestionParser.parse(raw)
        assertEquals("返事", parsed.cleanText)
        assertEquals("にゃーん", parsed.suggestionA)
        assertEquals("ふみゅ", parsed.suggestionB)
        assertEquals("ぱないの", parsed.suggestionC)
    }

    @Test
    fun `no marker leaves the body alone`() {
        val raw = "A: これは本文のセリフじゃ"
        val parsed = ChatSuggestionParser.parse(raw)
        assertEquals(raw, parsed.cleanText)
        assertFalse(parsed.hasSuggestions)
        assertNull(parsed.suggestionA)
    }

    @Test
    fun `applyTo writes suggestions onto the node`() {
        val node = ChatNode(text = "本文\n<<<SUGGESTIONS>>>\nA: あ\nB: い\nC: う\n<<</SUGGESTIONS>>>", isUser = false)
        ChatSuggestionParser.applyTo(node)
        assertEquals("本文", node.text)
        assertEquals("あ", node.suggestionA)
        assertEquals("い", node.suggestionB)
        assertEquals("う", node.suggestionC)
    }
}
