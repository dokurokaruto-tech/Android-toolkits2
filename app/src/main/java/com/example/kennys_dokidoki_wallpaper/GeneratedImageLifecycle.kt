package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧専用の生成画像に紐づく仮チャット／タグを、全画像への取り込みや削除でどう扱うか。
 */
object GeneratedImageLifecycle {
    data class Draft(
        val tags: Set<String> = emptySet(),
        val description: String? = null,
        val linkedChatId: String? = null
    )

    data class ImportPlan(
        val destKey: String,
        val tags: Set<String>,
        val description: String?,
        val chatId: String?,
        val persistedSessionName: String?
    )

    data class DeletePlan(
        val chatIdToDelete: String?,
        val unlinkKeys: List<String>
    )

    fun importDraft(
        destKey: String,
        draft: Draft,
        sessionName: String?
    ): ImportPlan {
        val persistedName = sessionName
            ?.removePrefix("⏳ ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return ImportPlan(
            destKey = destKey,
            tags = draft.tags,
            description = draft.description,
            chatId = draft.linkedChatId?.takeIf { it.isNotBlank() },
            persistedSessionName = persistedName
        )
    }

    fun deletePlan(
        imageKey: String,
        linkedChatId: String?,
        otherKeysUsingChat: Collection<String>
    ): DeletePlan {
        val others = otherKeysUsingChat.filter { it.isNotBlank() && it != imageKey }
        val shouldDeleteChat = !linkedChatId.isNullOrBlank() && others.isEmpty()
        return DeletePlan(
            chatIdToDelete = if (shouldDeleteChat) linkedChatId else null,
            unlinkKeys = listOf(imageKey)
        )
    }
}
