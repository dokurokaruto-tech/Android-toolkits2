package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInstructionPolicyTest {
    @Test
    fun splitsEverySourceInsteadOfJoining() {
        val sections = ChatInstructionPolicy.sections(
            ChatInstructionPolicy.Snapshot(
                personaName = "ケニー",
                personaItems = listOf("甘える", "短く話す"),
                imageDescription = "白いワンピース",
                tags = listOf("金髪" to "髪は金色。", "幼女" to "幼い見た目。"),
                memories = listOf("ドーナツが好き"),
                suggestEnabled = true,
                suggestExtra = "語尾をにゃ"
            )
        )
        assertEquals(ChatInstructionPolicy.Kind.ROLE, sections.first().kind)
        assertEquals(ChatInstructionPolicy.ROLE_TEXT, sections.first().body)
        assertEquals(2, sections.count { it.kind == ChatInstructionPolicy.Kind.USER })
        assertEquals("甘える", sections.first { it.kind == ChatInstructionPolicy.Kind.USER }.body)
        assertEquals("白いワンピース", sections.first { it.kind == ChatInstructionPolicy.Kind.IMAGE }.body)
        assertEquals(listOf("金髪", "幼女"), sections.filter { it.kind == ChatInstructionPolicy.Kind.TAG }.map { it.title })
        assertEquals("ドーナツが好き", sections.first { it.kind == ChatInstructionPolicy.Kind.MEMORY }.body)
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.SUGGEST && !it.empty })
        assertEquals("語尾をにゃ", sections.first { it.kind == ChatInstructionPolicy.Kind.SUGGEST_EXTRA }.body)
        assertFalse(sections.joinToString { it.body }.contains("甘える\n短く話す"))
    }

    @Test
    fun emptyPiecesStayVisibleAsUnset() {
        val sections = ChatInstructionPolicy.sections(ChatInstructionPolicy.Snapshot())
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.USER && it.empty })
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.IMAGE && it.empty })
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.TAG && it.empty })
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.MEMORY && it.empty })
        assertTrue(sections.any { it.kind == ChatInstructionPolicy.Kind.SUGGEST && it.empty })
        assertEquals(ChatInstructionCopy.EMPTY, ChatInstructionPolicy.preview(sections.first { it.empty }))
        assertEquals("使っている 1 / 6", ChatInstructionPolicy.countLine(sections))
    }

    @Test
    fun previewUsesTheFirstLine() {
        val section = ChatInstructionPolicy.Section(
            ChatInstructionPolicy.Kind.TAG,
            "ドヤ顔",
            "タグ",
            "胸を張る。\n鼻高々。"
        )
        assertEquals("胸を張る。", ChatInstructionPolicy.preview(section))
    }
}
