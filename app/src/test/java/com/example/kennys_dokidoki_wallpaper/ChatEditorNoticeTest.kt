package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEditorNoticeTest {
    private val started = ChatReplyNoticePolicy.Event.STARTED
    private val completed = ChatReplyNoticePolicy.Event.COMPLETED
    private val away = ChatReplyNoticePolicy.Visibility.AWAY
    private val viewing = ChatReplyNoticePolicy.Visibility.VIEWING
    private val on = ChatReplyNoticePolicy.Setting.ON

    @Test
    fun twoEventsOnlyOnce() {
        val policy = ChatReplyNoticePolicy()
        assertTrue(policy.accept(started, away, on))
        assertFalse(policy.accept(started, away, on))
        assertTrue(policy.accept(completed, away, on))
        assertFalse(policy.accept(completed, away, on))
    }

    @Test
    fun suppressedEventsAreNotReplayed() {
        val policy = ChatReplyNoticePolicy()
        assertFalse(policy.accept(started, viewing, on))
        assertFalse(policy.accept(started, away, on))
        assertFalse(policy.accept(completed, viewing, on))
        assertFalse(policy.accept(completed, away, on))
    }

    @Test
    fun finishCanNotifyAfterLeaving() {
        val policy = ChatReplyNoticePolicy()
        assertFalse(policy.accept(started, viewing, on))
        assertTrue(policy.accept(completed, away, on))
    }

    @Test
    fun emptyAndOffStaySilent() {
        assertFalse(ChatReplyNoticePolicy().accept(completed, away, on))
        val policy = ChatReplyNoticePolicy()
        assertFalse(policy.accept(started, away, ChatReplyNoticePolicy.Setting.OFF))
        assertFalse(policy.accept(completed, away, ChatReplyNoticePolicy.Setting.OFF))
    }

    @Test
    fun streamNeedsTerminalMarker() {
        val stream = ChatReplyStreamState()
        assertFalse(stream.completed("partial"))
        stream.done()
        assertFalse(stream.completed(""))
        assertTrue(stream.completed("reply"))
        stream.fail()
        stream.done()
        assertFalse(stream.completed("partial"))
    }

    @Test
    fun changedDraftCannotOverwrite() {
        val before = ConciergeEditorPolicy.Draft("card-id", ConciergeEditorPolicy.Kind.CARD, "name", "unsaved", "negative")
        assertTrue(ConciergeEditorPolicy.canApply(before, before.copy()))
        assertFalse(ConciergeEditorPolicy.canApply(before, null))
        assertFalse(ConciergeEditorPolicy.canApply(before, before.copy(key = "other")))
        assertFalse(ConciergeEditorPolicy.canApply(before, before.copy(main = "changed")))
        assertFalse(ConciergeEditorPolicy.canApply(before, before.copy(negative = "changed")))
        assertTrue(ConciergeEditorPolicy.context(before).contains("unsaved"))
    }

    @Test
    fun variantIdentityIsNotJustItsName() {
        val before = ConciergeEditorPolicy.Draft("variant-1", ConciergeEditorPolicy.Kind.TAG, "same name", "draft")
        assertFalse(ConciergeEditorPolicy.canApply(before, before.copy(key = "variant-2")))
    }
}
