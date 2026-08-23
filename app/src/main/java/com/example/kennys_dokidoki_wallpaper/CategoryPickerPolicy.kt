package com.example.kennys_dokidoki_wallpaper

/**
 * プロンプトカード編集のカテゴリーは、既存一覧からの選択のみ。
 */
object CategoryPickerPolicy {
    const val FALLBACK = "未分類"

    fun selectable(existing: Collection<String>, selected: String = ""): List<String> {
        val seen = linkedSetOf<String>()
        existing.map { it.trim() }.filter { it.isNotEmpty() }.forEach { seen.add(it) }
        selected.trim().takeIf { it.isNotEmpty() }?.let { seen.add(it) }
        if (seen.isEmpty()) seen.add(FALLBACK)
        return seen.toList()
    }

    fun defaultSelected(options: Collection<String>, selected: String): String {
        val trimmed = selected.trim()
        return when {
            trimmed.isNotEmpty() && trimmed in options -> trimmed
            trimmed.isNotEmpty() -> trimmed
            else -> options.firstOrNull() ?: FALLBACK
        }
    }
}
