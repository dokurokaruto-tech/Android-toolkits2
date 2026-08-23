package com.example.kennys_dokidoki_wallpaper

/**
 * 仮チャットは窓が透明なので、完成画が来るまで今のチャット壁紙が透ける。
 * 指定画像があるときは、先に黒で塞いでからその絵だけを出す。
 */
object ChatPinnedBackgroundPolicy {
    fun shouldCoverLiveWallpaper(pinnedImageUri: String?): Boolean =
        !pinnedImageUri.isNullOrBlank()

    fun loadModel(cachedOriginal: ByteArray?, pinnedUri: String): Any =
        cachedOriginal ?: pinnedUri

    fun thumbnailUri(explicitThumbnail: String?, entryThumbnail: String?): String? {
        val explicit = explicitThumbnail?.trim().orEmpty()
        if (explicit.isNotEmpty()) return explicit
        val fallback = entryThumbnail?.trim().orEmpty()
        return fallback.takeIf { it.isNotEmpty() }
    }
}
