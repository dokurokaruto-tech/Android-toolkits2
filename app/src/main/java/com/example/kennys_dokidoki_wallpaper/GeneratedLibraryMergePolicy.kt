package com.example.kennys_dokidoki_wallpaper

/** 閲覧リストへ、新しく完了した画像だけを先頭へ足す。見ていた位置はずらさない。 */
object GeneratedLibraryMergePolicy {
    fun prependNewUrls(existing: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return existing
        val known = existing.toSet()
        val fresh = incoming.filter { it.isNotBlank() && it !in known }
        return if (fresh.isEmpty()) existing else fresh + existing
    }

    fun shiftIndexAfterPrepend(currentIndex: Int, existingCount: Int, addedCount: Int): Int {
        if (addedCount <= 0 || existingCount <= 0) return currentIndex.coerceAtLeast(0)
        return (currentIndex + addedCount).coerceAtLeast(0)
    }
}
