package com.example.kennys_dokidoki_wallpaper

/**
 * チャット画面を開き直したとき、メモリ上の会話を消してよいかを決めるポリシー。
 *
 * キャラ画像が増えると SharedPreferences への巨大保存が失敗し、画像→チャットの紐づけが落ちることがある。
 * その状態で画面を再表示すると、一瞬だけ作ったチャットが出たあと空に戻ってしまう。
 * 紐づけが欠けていてもメモリに会話が残っていれば消さず、再紐づけする。
 */
object ChatRestorePolicy {
    enum class Action {
        KEEP_MEMORY,
        LOAD_DISK,
        RELINK_MEMORY,
        START_FRESH
    }

    data class Decision(
        val action: Action,
        val sessionId: String?
    )

    fun decide(
        imageChanged: Boolean,
        resolvedChatId: String?,
        currentChatId: String?,
        memoryHasMessages: Boolean
    ): Decision {
        if (!resolvedChatId.isNullOrBlank()) {
            if (!imageChanged && currentChatId == resolvedChatId && memoryHasMessages) {
                return Decision(Action.KEEP_MEMORY, resolvedChatId)
            }
            return Decision(Action.LOAD_DISK, resolvedChatId)
        }

        if (!imageChanged && memoryHasMessages && !currentChatId.isNullOrBlank()) {
            return Decision(Action.RELINK_MEMORY, currentChatId)
        }

        if (imageChanged || currentChatId != null) {
            return Decision(Action.START_FRESH, null)
        }

        return Decision(Action.KEEP_MEMORY, currentChatId)
    }
}
