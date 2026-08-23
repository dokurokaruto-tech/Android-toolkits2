package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧の日付フォルダ画面の見出しと日付表示。
 */
object GeneratedFolderPickerPolicy {
    const val TITLE = "閲覧"
    const val SUBTITLE = "日付フォルダ"

    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

    fun formatFolderLabel(raw: String): String {
        val name = raw.trim()
        val match = DATE.find(name) ?: return name
        val year = match.groupValues[1]
        val month = match.groupValues[2].toIntOrNull() ?: return name
        val day = match.groupValues[3].toIntOrNull() ?: return name
        val suffix = name.removePrefix(match.value).trim()
        val formatted = "${year}年${month}月${day}日"
        return if (suffix.isEmpty()) formatted else "$formatted $suffix"
    }

    fun countLabel(count: Int): String = "${count} 枚"
}
