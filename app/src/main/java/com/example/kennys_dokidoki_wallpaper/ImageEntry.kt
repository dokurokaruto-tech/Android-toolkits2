package com.example.kennys_dokidoki_wallpaper

import android.graphics.RectF
import android.net.Uri

data class ImageEntry(
    var uri: Uri,
    val tags: MutableSet<String> = mutableSetOf(),
    var isActive: Boolean = true,
    var croppedUri: Uri? = null, // 後方互換性のため残すけど、新規は cropRect を使うわ
    var linkedChatId: String? = null,
    var cropRect: RectF? = null, // クロップ範囲（0.0〜1.0の比率で保存するわ）
    var description: String? = null,
    // PC閲覧専用。永続DBには保存せず、一覧では圧縮版、全画面では uri の原寸を使う。
    var thumbnailUri: Uri? = null
) {
    val displayUri: Uri
        get() = croppedUri ?: uri
}