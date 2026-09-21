package com.example.kennys_dokidoki_wallpaper

/**
 * 返信のあとにサジェストが続くあいだ、吹き出しが止まったように見えるのを防ぐ。
 * タグ以降は本文に出さないので、その間は候補欄で待つ。
 */
object ChatSuggestionUiPolicy {
    enum class Phase { HIDDEN, GENERATING, FAILED, READY }

    const val LOADING_LABEL = "候補を作っている…"
    const val FAILED_TOAST = "サジェストを作れませんでした。"
    const val RETRY_LABEL = "↻ 履歴からサジェストを作り直す"

    fun hasChoices(a: String?, b: String?, c: String?): Boolean {
        return !a.isNullOrBlank() || !b.isNullOrBlank() || !c.isNullOrBlank()
    }

    fun phase(
        enabled: Boolean,
        isLastMessage: Boolean,
        isUser: Boolean,
        generatingThis: Boolean,
        retryingThis: Boolean = false,
        rawText: String,
        suggestionA: String?,
        suggestionB: String?,
        suggestionC: String?
    ): Phase {
        if (!enabled || isUser || !isLastMessage) return Phase.HIDDEN
        if (retryingThis) return Phase.GENERATING
        if (hasChoices(suggestionA, suggestionB, suggestionC)) return Phase.READY
        if (generatingThis) {
            return if (ChatSuggestionParser.markerIndex(rawText) >= 0) Phase.GENERATING else Phase.HIDDEN
        }
        // 生成は終わったのに候補がない → 作り直しボタンを出す
        if (isFinishedReply(rawText)) return Phase.FAILED
        return Phase.HIDDEN
    }

    /** 中断・エラー・仮表示のままの吹き出しには作り直しを出さない。 */
    private fun isFinishedReply(rawText: String): Boolean =
        !ChatInterruptPolicy.isPendingPlaceholder(rawText) &&
            rawText != ChatInterruptPolicy.INTERRUPTED_TEXT &&
            !rawText.startsWith("【エラー】")

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
