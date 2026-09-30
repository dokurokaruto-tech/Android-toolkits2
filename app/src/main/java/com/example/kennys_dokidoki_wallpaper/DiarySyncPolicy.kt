package com.example.kennys_dokidoki_wallpaper

/**
 * ローカルと Drive のどちらが新しいかを決める規則。
 * 端末と Drive の時計はぴったり合わないので、わずかな差は「同じ」とみなす。
 */
object DiarySyncPolicy {

    /** これ以内の差は時計のズレとして無視する */
    const val CLOCK_SKEW_MS = 5_000L

    const val ENTRY_SUFFIX = ".json"
    const val ENTRY_MIME = "application/json"
    const val IMAGE_MIME = "image/*"
    const val IMAGE_PREFIX = "img_"

    fun entryFileName(dateKey: String): String = dateKey + ENTRY_SUFFIX

    fun dateKeyOf(driveFileName: String): String? =
        if (driveFileName.endsWith(ENTRY_SUFFIX)) driveFileName.removeSuffix(ENTRY_SUFFIX) else null

    fun imageFileName(localName: String): String = IMAGE_PREFIX + localName

    fun localImageName(driveFileName: String): String? =
        if (driveFileName.startsWith(IMAGE_PREFIX)) driveFileName.removePrefix(IMAGE_PREFIX) else null

    fun shouldPull(localModifiedMs: Long, remoteModifiedMs: Long): Boolean =
        remoteModifiedMs > localModifiedMs + CLOCK_SKEW_MS

    fun shouldPush(localModifiedMs: Long, remoteModifiedMs: Long): Boolean =
        localModifiedMs > remoteModifiedMs + CLOCK_SKEW_MS
}
