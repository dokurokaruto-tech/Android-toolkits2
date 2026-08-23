package com.example.kennys_dokidoki_wallpaper

/**
 * 返信のあとにサジェストが続くあいだ、吹き出しが止まったように見えるのを防ぐ。
 * タグ以降は本文に出さないので、その間は候補欄で待つ。
 */
object ChatSuggestionUiPolicy {
    enum class Phase { HIDDEN, GENERATING, READY }

    const val LOADING_LABEL = "候補を作っている…"
    const val FAILED_TOAST = "サジェストを作れませんでした。"

    fun hasChoices(a: String?, b: String?, c: String?): Boolean {
        return !a.isNullOrBlank() || !b.isNullOrBlank() || !c.isNullOrBlank()
    }

    fun phase(
        enabled: Boolean,
        isLastMessage: Boolean,
        isUser: Boolean,
        generatingThis: Boolean,
        rawText: String,
        suggestionA: String?,
        suggestionB: String?,
        suggestionC: String?
    ): Phase {
        if (!enabled || isUser || !isLastMessage) return Phase.HIDDEN
        if (hasChoices(suggestionA, suggestionB, suggestionC)) return Phase.READY
        if (generatingThis && ChatSuggestionParser.markerIndex(rawText) >= 0) {
            return Phase.GENERATING
        }
        return Phase.HIDDEN
    }

    fun shouldToastFailure(
        enabled: Boolean,
        isComplete: Boolean,
        isError: Boolean,
        suggestionA: String?,
        suggestionB: String?,
        suggestionC: String?
    ): Boolean {
        if (!enabled || !isComplete || isError) return false
        return !hasChoices(suggestionA, suggestionB, suggestionC)
    }
}
