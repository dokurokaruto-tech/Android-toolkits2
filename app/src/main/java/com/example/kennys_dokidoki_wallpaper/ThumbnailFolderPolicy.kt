package com.example.kennys_dokidoki_wallpaper

/**
 * 保存フォルダ一覧画面の表示ルール。File の実操作は呼び出し側に置く。
 */
object ThumbnailFolderPolicy {

    data class Entry(val name: String, val bytes: Long, val lastModified: Long = 0)

    /** 更新時刻の新しい順。同一時刻は名前の降順で固定する。 */
    fun sorted(entries: List<Entry>): List<Entry> =
        entries.sortedWith(compareByDescending<Entry> { it.lastModified }.thenByDescending { it.name })

    fun kindLabel(name: String): String = when {
        name.startsWith("card_") -> "カード"
        name.startsWith("preset_") -> "プリセット"
        name.startsWith("lib_") -> "閲覧"
        else -> "その他"
    }

    /** lib_lib_<hash>.jpg はハッシュしか意味が無いので要約する。 */
    fun displayName(name: String): String {
        val base = name.removeSuffix(".part").removeSuffix(".jpg")
        val parts = base.split('_')
        return when {
            parts.firstOrNull() == "lib" -> "閲覧キャッシュ #${parts.last().take(8)}"
            parts.size >= 3 -> parts[1]
            else -> base
        }
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / 1048576f)
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
        else -> "${bytes}B"
    }
}
