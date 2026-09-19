package com.example.kennys_dokidoki_wallpaper

/**
 * 一括サムネイル生成の対象一覧。設定済みかどうかで初期選択と表示を分ける。
 */
object BulkThumbnailPickerPolicy {
    const val STATUS_SET = "設定済み"
    const val STATUS_MISSING = "未設定"

    enum class Filter { ALL, MISSING, SET }

    data class Entry(
        val id: String,
        val label: String,
        val thumbnail: String?,
        val selected: Boolean
    ) {
        val hasThumbnail: Boolean get() = hasThumbnail(thumbnail)
        val status: String get() = if (hasThumbnail) STATUS_SET else STATUS_MISSING
    }

    fun hasThumbnail(uri: String?): Boolean {
        val trimmed = uri?.trim()
        if (trimmed.isNullOrEmpty()) return false
        // 差し先のファイルが消えた・空・壊れは「!」のまま。未設定として扱い、
        // 一括生成の既定選択と「未設定」フィルタに含める。
        return !ThumbnailLocalCachePolicy.isBrokenLocalImage(trimmed)
    }

    fun defaultSelected(hasThumbnail: Boolean): Boolean = !hasThumbnail

    fun entry(id: String, label: String, thumbnail: String?): Entry {
        val trimmed = thumbnail?.trim().orEmpty().ifEmpty { null }
        return Entry(
            id = id,
            label = label,
            thumbnail = trimmed,
            selected = defaultSelected(hasThumbnail(trimmed))
        )
    }

    fun visible(entries: List<Entry>, filter: Filter): List<Entry> = when (filter) {
        Filter.ALL -> entries
        Filter.MISSING -> entries.filter { !it.hasThumbnail }
        Filter.SET -> entries.filter { it.hasThumbnail }
    }

    fun toggle(entry: Entry): Entry = entry.copy(selected = !entry.selected)

    fun setAll(entries: List<Entry>, selected: Boolean): List<Entry> =
        entries.map { it.copy(selected = selected) }

    fun selectedIds(entries: List<Entry>): List<String> =
        entries.filter { it.selected }.map { it.id }

    fun summary(entries: List<Entry>): String {
        val set = entries.count { it.hasThumbnail }
        val missing = entries.size - set
        val picked = entries.count { it.selected }
        return "全${entries.size}件。設定済み ${set}件、未設定 ${missing}件。生成 ${picked}件。"
    }
}
