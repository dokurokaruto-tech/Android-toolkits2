package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPromptStreamPolicyTest {
    @Test
    fun userMessageKeepsLegacyShape() {
        assertEquals(
            "ターゲット: ドヤ顔\n指示: もっと短く\n既存設定: 今の文",
            TagPromptStreamPolicy.userMessage("ドヤ顔", "もっと短く", "今の文", useTagName = true, useExisting = true)
        )
        assertEquals(
            "ターゲット: \n指示: 補足だけ",
            TagPromptStreamPolicy.userMessage("ドヤ顔", "補足だけ", "下書き", useTagName = false, useExisting = false)
        )
    }

    @Test
    fun sseLinesYieldDataOrDone() {
        assertEquals(
            TagPromptStreamPolicy.SseEvent.Data("{\"choices\":[]}"),
            TagPromptStreamPolicy.parseSseLine("data: {\"choices\":[]}")
        )
        assertEquals(TagPromptStreamPolicy.SseEvent.Done, TagPromptStreamPolicy.parseSseLine("data: [DONE]"))
        assertNull(TagPromptStreamPolicy.parseSseLine(": keep-alive"))
        assertNull(TagPromptStreamPolicy.parseSseLine(""))
        assertNull(TagPromptStreamPolicy.parseSseLine("event: message"))
    }

    @Test
    fun contentComesFromDeltaOnly() {
        assertEquals(
            "こんにちは",
            TagPromptStreamPolicy.contentFromChunk(
                """{"choices":[{"delta":{"content":"こんにちは"}}]}"""
            )
        )
        assertEquals(
            "",
            TagPromptStreamPolicy.contentFromChunk(
                """{"choices":[{"delta":{"role":"assistant"}}]}"""
            )
        )
        assertEquals("", TagPromptStreamPolicy.contentFromChunk("not-json"))
    }

    @Test
    fun failedInstructionAppendsWithAClearGap() {
        assertEquals("補足", TagPromptStreamPolicy.applyFailedInstruction("", "  補足  "))
        assertEquals("今の文。", TagPromptStreamPolicy.applyFailedInstruction("今の文。", "   "))
        assertEquals(
            "今の文。最後の一文。\n\nもっと短くして",
            TagPromptStreamPolicy.applyFailedInstruction("今の文。最後の一文。  \n", "もっと短くして")
        )
    }

    @Test
    fun streamRequestMarksStreamTrue() {
        val body = org.json.JSONObject(
            TagPromptStreamClient.requestBody(
                "grok-4-1-fast-non-reasoning",
                "system",
                "user",
                "data:image/jpeg;base64,abc"
            )
        )
        assertTrue(body.getBoolean("stream"))
        val user = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", user.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,abc",
            user.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun sseChunksAccumulateInOrder() {
        val lines = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"今\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"回\"}}]}",
            "data: [DONE]"
        )
        val built = StringBuilder()
        for (line in lines) {
            when (val event = TagPromptStreamPolicy.parseSseLine(line)) {
                TagPromptStreamPolicy.SseEvent.Done -> break
                is TagPromptStreamPolicy.SseEvent.Data -> built.append(TagPromptStreamPolicy.contentFromChunk(event.payload))
                null -> Unit
            }
        }
        assertEquals("今回", built.toString())
    }
}
