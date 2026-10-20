package com.example.kennys_dokidoki_wallpaper

import java.time.LocalDate

/** ZIP内のパス検証と、既存データを上書きするかの判断。 */
internal object LibraryBackupPolicy {

    private const val DIARY_PREFIX = "diary/"
    private const val NOTES_PREFIX = "notes/"
    private const val IMAGES_PREFIX = "images/"
    private const val JSON_SUFFIX = ".json"
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val safeImageName = Regex("[A-Za-z0-9_-]{1,128}\\.img")

    data class NoteAddress(val kind: LibraryNoteKind, val id: String)

    fun diaryDateOf(path: String): String? {
        if (!path.startsWith(DIARY_PREFIX) || !path.endsWith(JSON_SUFFIX)) {
            return null
        }
        val date = path.removePrefix(DIARY_PREFIX).removeSuffix(JSON_SUFFIX)
        if (!datePattern.matches(date)) {
            return null
        }
        return runCatching { LocalDate.parse(date).takeIf { it.toString() == date }?.let { date } }
            .getOrNull()
    }

    fun noteAddressOf(path: String): NoteAddress? {
        val parts = path.split('/')
        if (parts.size != 3 || parts[0] != NOTES_PREFIX.removeSuffix("/") || !parts[2].endsWith(JSON_SUFFIX)) {
            return null
        }
        val kind = LibraryNoteKind.fromArchiveKey(parts[1]) ?: return null
        val id = parts[2].removeSuffix(JSON_SUFFIX)
        return id.takeIf { LibraryNoteStore.isValidId(it) }?.let { NoteAddress(kind, it) }
    }

    fun imageNameOf(path: String): String? {
        if (!path.startsWith(IMAGES_PREFIX)) {
            return null
        }
        val name = path.removePrefix(IMAGES_PREFIX)
        return name.takeIf { safeImageName.matches(it) }
    }

    /** Manifestにはファイルの正確な更新時刻を記録するため、丸め誤差なしで比較する。 */
    fun shouldImport(localModifiedMs: Long, backupModifiedMs: Long): Boolean =
        localModifiedMs == 0L || backupModifiedMs > localModifiedMs
}
