package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatVoicePolicyTest {
    private val voice = TagVoice("a".repeat(64), "voice.wav", "こんにちは")

    @Test fun oneVoiceIsSelected() {
        assertEquals(voice, ChatVoicePolicy.requireVoice(listOf(ChatVoice("Alice", voice))))
    }

    @Test fun twoTagsEvenWithSameSampleAreRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ChatVoicePolicy.requireVoice(listOf(ChatVoice("Alice", voice), ChatVoice("Bob", voice)))
        }
        assertTrue(error.message!!.contains("Alice"))
        assertTrue(error.message!!.contains("Bob"))
    }

    @Test fun noVoiceAndUnknownHistoryAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChatVoicePolicy.requireVoice(null) }
        assertThrows(IllegalArgumentException::class.java) { ChatVoicePolicy.requireVoice(emptyList()) }
    }

    @Test fun inheritedTagsCountAndSnapshotSurvivesEdits() {
        try {
            TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()).put("Bob", voice.toJson()))
            TagManager.impliedTagsMap["Alice"] = setOf("Bob")
            TagManager.impliedTagsMap["Bob"] = setOf("Alice")
            val snapshot = TagManager.voiceSnapshot(setOf("Alice", "Bob"))
            assertEquals(2, snapshot.size)
            TagManager.restoreVoices(JSONObject())
            TagManager.impliedTagsMap.clear()
            assertThrows(IllegalArgumentException::class.java) { ChatVoicePolicy.requireVoice(snapshot) }
        } finally {
            TagManager.restoreVoices(JSONObject())
            TagManager.impliedTagsMap.clear()
        }
    }

    @Test fun historyRoundTripKeepsVoiceAndReadiness() {
        val node = ChatNode(text = "Hello", isUser = false,
            ttsVoices = listOf(ChatVoice("Alice", voice)), ttsReady = true)
        val tree = ChatTree(mutableMapOf(node.id to node), node.id)
        val restored = ChatSessionManager.deserializeTree(ChatSessionManager.serializeTree(tree))
        assertEquals(node.ttsVoices, restored.nodes[node.id]!!.ttsVoices)
        assertTrue(restored.nodes[node.id]!!.ttsReady)
    }

    @Test fun legacyHistoryHasNoInventedVoice() {
        val restored = ChatSessionManager.deserializeTree("""{"nodes":{"old":{"text":"Hello","isUser":false}},"currentNodeId":"old"}""")
        assertNull(restored.nodes["old"]!!.ttsVoices)
        assertFalse(restored.nodes["old"]!!.ttsReady)
    }

    @Test fun brokenVoiceMetadataDoesNotEraseChat() {
        val restored = ChatSessionManager.deserializeTree("""{"nodes":{"old":{"text":"Hello","isUser":false,"ttsVoices":[{}]}},"currentNodeId":"old"}""")
        assertEquals("Hello", restored.nodes["old"]!!.text)
        assertNull(restored.nodes["old"]!!.ttsVoices)
    }

    @Test fun emptySnapshotIsNotUnknown() {
        val node = ChatNode(text = "Hello", isUser = false, ttsVoices = emptyList())
        val tree = ChatTree(mutableMapOf(node.id to node), node.id)
        val restored = ChatSessionManager.deserializeTree(ChatSessionManager.serializeTree(tree))
        assertEquals(emptyList<ChatVoice>(), restored.nodes[node.id]!!.ttsVoices)
    }

    @Test fun speechOmitsSuggestionsAndImages() {
        val raw = "<think>秘密</think>こんにちは。![image](https://example.com/a.png)<<<SUGGESTIONS>>>A: 次は？"
        assertEquals("こんにちは。", ChatVoicePolicy.speechText(raw))
    }

    @Test fun emptyAndLongSpeechRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChatVoicePolicy.speechText(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            ChatVoicePolicy.speechText("あ".repeat(ChatVoicePolicy.MAX_TEXT_LENGTH + 1))
        }
    }
}
