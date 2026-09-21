package com.example.kennys_dokidoki_wallpaper

/**
 * OpenRouterモデル一覧の表示可否。
 * `:batch` 系は返信まで最大24時間かかるため、チャット用途では選択肢から外す。
 */
internal object OpenRouterModelVisibility {
    private const val BATCH_SUFFIX = ":batch"

    fun isSelectable(modelId: String): Boolean =
        !modelId.endsWith(BATCH_SUFFIX, ignoreCase = true)
}
