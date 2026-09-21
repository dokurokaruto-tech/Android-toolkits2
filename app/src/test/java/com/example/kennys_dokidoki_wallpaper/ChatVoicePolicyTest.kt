package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.After

class ChatVoicePolicyTest {
    private val voice = TagVoice("a".repeat(64), "voice.wav", "こんにちは")

    @After fun clearVoiceState() {
        TagManager.restoreVoices(JSONObject())
        TagManager.restoreVoiceIds(JSONObject())
        TagManager.impliedTagsMap.clear()
    }

    @Test fun snapshotKeepsIds() {
        TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()))
        val before = TagManager.voiceSnapshot(setOf("Alice"))
        val id = before.single().tagId
        assertTrue(Regex("[0-9a-f]{32}").matches(id))
        TagManager.restoreVoices(JSONObject().put("Alice", voice.copy(sampleId = "b".repeat(64)).toJson()))
        val after = TagManager.voiceSnapshot(setOf("Alice")).single()
        assertEquals(id, after.tagId)
        assertEquals(voice.sampleId, before.single().voice.sampleId)
        assertEquals("b".repeat(64), after.voice.sampleId)
        assertEquals(before, ChatVoicePolicy.decode(ChatVoicePolicy.encode(before)))
    }

    @Test fun unlinkRetainsPcIdentity() {
        TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()))
        val id = TagManager.voiceTagId("Alice")
        TagManager.restoreVoices(JSONObject())
        assertEquals(id, TagManager.voiceTagId("Alice"))
        assertTrue(TagManager.voiceSnapshot(setOf("Alice")).isEmpty())
    }

    @Test fun sameAudioHasDistinctTags() {
        TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()).put("Bob", voice.toJson()))
        assertNotEquals(TagManager.voiceTagId("Alice"), TagManager.voiceTagId("Bob"))
    }

    @Test fun backupKeepsPcIdentity() {
        TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()))
        val id = TagManager.voiceTagId("Alice")
        val voices = TagManager.exportVoices()
        val ids = TagManager.exportVoiceIds()
        TagManager.restoreVoices(JSONObject())
        TagManager.restoreVoiceIds(JSONObject())
        TagManager.restoreVoiceIds(ids)
        TagManager.restoreVoices(voices)
        assertEquals(id, TagManager.voiceSnapshot(setOf("Alice")).single().tagId)
    }

    @Test fun invalidIdGetsMigrated() {
        TagManager.restoreVoiceIds(JSONObject().put("Alice", "../invalid"))
        TagManager.restoreVoices(JSONObject().put("Alice", voice.toJson()))
        assertTrue(Regex("[0-9a-f]{32}").matches(TagManager.voiceTagId("Alice")!!))
    }

    @Test fun legacySnapshotKeepsSample() {
        val old = org.json.JSONArray().put(JSONObject().put("tag", "Alice").put("voice", voice.toJson()))
        val restored = ChatVoicePolicy.decode(old).single()
        assertEquals("", restored.tagId)
        assertEquals(voice, restored.voice)
    }

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
            ttsVoices = listOf(ChatVoice("Alice", voice, "c".repeat(32))), ttsReady = true)
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
