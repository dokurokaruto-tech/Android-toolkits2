package com.example.kennys_dokidoki_wallpaper

private var checks = 0
private fun expect(value: Boolean) {
    check(value) { "Check ${checks + 1} failed" }
    checks++
}

fun main() {
    val started = ChatReplyNoticePolicy.Event.STARTED
    val completed = ChatReplyNoticePolicy.Event.COMPLETED
    val away = ChatReplyNoticePolicy.Visibility.AWAY
    val viewing = ChatReplyNoticePolicy.Visibility.VIEWING
    val on = ChatReplyNoticePolicy.Setting.ON
    val off = ChatReplyNoticePolicy.Setting.OFF
    val background = ChatReplyNoticePolicy()
    expect(background.accept(started, away, on))
    expect(!background.accept(started, away, on))
    expect(background.accept(completed, away, on))
    expect(!background.accept(completed, away, on))

    val foreground = ChatReplyNoticePolicy()
    expect(!foreground.accept(started, viewing, on))
    expect(!foreground.accept(started, away, on))
    expect(!foreground.accept(completed, viewing, on))
    expect(!foreground.accept(completed, away, on))

    val leaving = ChatReplyNoticePolicy()
    expect(!leaving.accept(started, viewing, on))
    expect(leaving.accept(completed, away, on))
    val returning = ChatReplyNoticePolicy()
    expect(returning.accept(started, away, on))
    expect(!returning.accept(completed, viewing, on))
    val disabled = ChatReplyNoticePolicy()
    expect(!disabled.accept(started, away, off))
    expect(!disabled.accept(completed, away, off))
    expect(!ChatReplyNoticePolicy().accept(completed, away, on))

    val stream = ChatReplyStreamState()
    expect(!stream.completed("partial"))
    stream.finishReason(null)
    expect(!stream.completed("partial"))
    stream.done()
    expect(!stream.completed(" \n"))
    expect(stream.completed("reply"))
    stream.fail()
    stream.done()
    expect(!stream.completed("reply"))
    val stopped = ChatReplyStreamState()
    stopped.finishReason("stop")
    expect(stopped.completed("reply"))
    val filtered = ChatReplyStreamState()
    filtered.finishReason("content_filter")
    filtered.done()
    expect(!filtered.completed("partial"))

    val card = ConciergeEditorPolicy.Draft("card-id", ConciergeEditorPolicy.Kind.CARD, "draft name", "unsaved main", "unsaved negative")
    expect(ConciergeEditorPolicy.canApply(card, card.copy()))
    expect(!ConciergeEditorPolicy.canApply(card, null))
    expect(!ConciergeEditorPolicy.canApply(card, card.copy(key = "other-id")))
    expect(!ConciergeEditorPolicy.canApply(card, card.copy(main = "new draft")))
    expect(!ConciergeEditorPolicy.canApply(card, card.copy(negative = "new negative")))
    expect(!ConciergeEditorPolicy.canApply(card, card.copy(label = "renamed")))
    expect(ConciergeEditorPolicy.context(card).contains("unsaved main"))
    expect(ConciergeEditorPolicy.context(card).contains("unsaved negative"))
    expect(ConciergeEditorPolicy.context(card).contains("card-id"))
    val tag = card.copy(key = "tag/variant-2", kind = ConciergeEditorPolicy.Kind.TAG, label = "tag / personality 2", negative = "")
    expect(ConciergeEditorPolicy.canApply(tag, tag.copy()))
    expect(!ConciergeEditorPolicy.canApply(tag, tag.copy(key = "tag/variant-1")))
    expect(ConciergeEditorPolicy.context(tag).contains("personality 2"))
    println("$checks editor/notification checks passed")
}
