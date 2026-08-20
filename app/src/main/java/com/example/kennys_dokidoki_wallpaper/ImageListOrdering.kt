package com.example.kennys_dokidoki_wallpaper

/**
 * 全画像一覧の先頭に、いま壁紙として出している画像を固定するための並べ替え。
 */
object ImageListOrdering {
    fun <T> pinToFront(items: List<T>, pinned: List<T?>, key: (T) -> String): List<T> {
        val pinnedKeys = linkedSetOf<String>()
        val front = mutableListOf<T>()
        for (candidate in pinned) {
            if (candidate == null) continue
            val k = key(candidate)
            if (k.isBlank() || k in pinnedKeys) continue
            val match = items.find { key(it) == k } ?: continue
            front.add(match)
            pinnedKeys.add(k)
        }
        val rest = items.filter { key(it) !in pinnedKeys }
        return front + rest
    }
}
