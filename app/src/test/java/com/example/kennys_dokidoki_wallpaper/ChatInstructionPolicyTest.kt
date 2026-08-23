package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInstructionPolicyTest {
    @Test
    fun groupsLeavesUnderCategories() {
        val categories = ChatInstructionPolicy.categories(
            ChatInstructionPolicy.Snapshot(
                personaName = "ケニー",
                personaItems = listOf("p1" to "甘える", "p2" to "短く話す"),
                imageDescription = "白いワンピース",
                tags = listOf("金髪" to "髪は金色。", "幼女" to "幼い見た目。"),
                memories = listOf("ドーナツが好き"),
                suggestEnabled = true,
                suggestExtra = "語尾をにゃ"
            )
        )
        assertEquals(
            listOf(
                ChatInstructionPolicy.Kind.ROLE,
                ChatInstructionPolicy.Kind.USER,
                ChatInstructionPolicy.Kind.IMAGE,
                ChatInstructionPolicy.Kind.TAG,
                ChatInstructionPolicy.Kind.MEMORY,
                ChatInstructionPolicy.Kind.SUGGEST
            ),
            categories.map { it.kind }
        )
        val user = categories.first { it.kind == ChatInstructionPolicy.Kind.USER }
        assertEquals(listOf("甘える", "短く話す"), user.leaves.map { it.body })
        assertEquals("p1", user.leaves.first().target.key)
        assertEquals(listOf("金髪", "幼女"), categories.first { it.kind == ChatInstructionPolicy.Kind.TAG }.leaves.map { it.title })
    }

    @Test
    fun collapsedListHidesLeavesUntilToggled() {
        val categories = ChatInstructionPolicy.categories(
            ChatInstructionPolicy.Snapshot(personaItems = listOf("p1" to "甘える", "p2" to "短く話す"))
        )
        val closed = ChatInstructionPolicy.visibleRows(categories, emptySet())
        assertEquals(6, closed.size)
        assertTrue(closed.all { it is ChatInstructionPolicy.Row.Group })
        val opened = ChatInstructionPolicy.visibleRows(
            categories,
            ChatInstructionPolicy.toggleExpanded(emptySet(), ChatInstructionPolicy.Kind.USER)
        )
        assertEquals(8, opened.size)
        assertEquals(
            listOf("甘える", "短く話す"),
            opened.filterIsInstance<ChatInstructionPolicy.Row.Item>().map { it.leaf.body }
        )
        val closedAgain = ChatInstructionPolicy.visibleRows(
            categories,
            ChatInstructionPolicy.toggleExpanded(setOf(ChatInstructionPolicy.Kind.USER), ChatInstructionPolicy.Kind.USER)
        )
        assertEquals(6, closedAgain.size)
    }

    @Test
    fun emptyPiecesStayVisibleAsUnset() {
        val categories = ChatInstructionPolicy.categories(ChatInstructionPolicy.Snapshot())
        assertTrue(categories.first { it.kind == ChatInstructionPolicy.Kind.USER }.leaves.single().empty)
        assertTrue(categories.first { it.kind == ChatInstructionPolicy.Kind.IMAGE }.leaves.single().empty)
        assertTrue(categories.first { it.kind == ChatInstructionPolicy.Kind.TAG }.leaves.single().empty)
        assertTrue(categories.first { it.kind == ChatInstructionPolicy.Kind.MEMORY }.leaves.single().empty)
        assertTrue(categories.first { it.kind == ChatInstructionPolicy.Kind.SUGGEST }.leaves.single().empty)
        assertEquals(
            ChatInstructionCopy.EMPTY,
            ChatInstructionPolicy.preview(categories.first { it.kind == ChatInstructionPolicy.Kind.USER }.leaves.single())
        )
        assertEquals("使っている 1 / 6", ChatInstructionPolicy.countLine(categories))
    }

    @Test
    fun storedRoleAndSuggestFallBackToDefaults() {
        assertEquals(ChatInstructionPolicy.ROLE_TEXT, ChatInstructionPolicy.roleText("  "))
        assertEquals("役割を変える", ChatInstructionPolicy.roleText("役割を変える"))
        assertEquals(ChatInstructionPolicy.DEFAULT_GENERATION_SUGGEST, ChatInstructionPolicy.suggestText(null))
        assertEquals(
            ChatInstructionPolicy.DEFAULT_GENERATION_SUGGEST,
            ChatInstructionPolicy.suggestText(ChatInstructionPolicy.LEGACY_EDITOR_SUGGEST)
        )
        assertTrue(ChatInstructionPolicy.suggestText(null).contains("最重要：システム指令"))
    }

    @Test
    fun editorAndGenerationShareTheSameSuggestCommand() {
        val shown = ChatInstructionPolicy.suggestText(null)
        val sent = ChatInstructionPolicy.generationSuggestRules(null, "")
        assertEquals(shown, sent)
        assertTrue(shown.contains("<<<SUGGESTIONS>>>"))
        assertTrue(shown.contains("15文字以内"))
    }

    @Test
    fun savedSuggestRulesReplaceTheHardcodedFifteenLimit() {
        val saved = ChatInstructionPolicy.DEFAULT_GENERATION_SUGGEST.replace("15文字以内", "50文字以内")
        val sent = ChatInstructionPolicy.generationSuggestRules(saved, "語尾をにゃ")
        assertTrue(sent.contains("50文字以内"))
        assertFalse(sent.contains("15文字以内"))
        assertTrue(sent.contains("語尾をにゃ"))
        assertTrue(sent.contains("最重要：システム指令"))
        val fallback = ChatInstructionPolicy.generationSuggestRules(null, "")
        assertTrue(fallback.contains("15文字以内"))
        val applied = ChatInstructionPolicy.applySuggestToUserText("こんにちは", true, saved, "")
        assertTrue(applied.startsWith("こんにちは"))
        assertTrue(applied.contains("50文字以内"))
        assertEquals("こんにちは", ChatInstructionPolicy.applySuggestToUserText("こんにちは", false, saved, ""))
    }

    @Test
    fun legacyShortTemplateIsNotSentInPlaceOfTheRealCommand() {
        val sent = ChatInstructionPolicy.generationSuggestRules(
            ChatInstructionPolicy.LEGACY_EDITOR_SUGGEST,
            ""
        )
        assertEquals(ChatInstructionPolicy.DEFAULT_GENERATION_SUGGEST, sent)
        val customizedShort = ChatInstructionPolicy.LEGACY_EDITOR_SUGGEST.replace("15文字以内", "50文字以内")
        val customSent = ChatInstructionPolicy.generationSuggestRules(customizedShort, "")
        assertTrue(customSent.contains("50文字以内"))
        assertFalse(customSent.contains("最重要：システム指令"))
    }
}
