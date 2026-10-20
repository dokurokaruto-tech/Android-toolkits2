package com.example.kennys_dokidoki_wallpaper

import java.time.LocalDate

internal data class LibraryBackupCounts(
    val diaries: Int,
    val essays: Int,
    val wishLists: Int,
    val images: Int,
    val payloadBytes: Long = 0L
) {
    val totalRecords: Int get() = diaries + essays + wishLists
}

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

/** 端末データが急減したとき、最後の正常なバックアップを誤って空にしないための保護。 */
internal object LibraryBackupSafetyPolicy {

    fun overwriteWarning(previous: LibraryBackupCounts?, current: LibraryBackupCounts): String? {
        if (previous == null) return null
        if (previous.diaries > 0 && current.diaries == 0) {
            return "以前のバックアップには日記がありますが、端末側の日記が0件です。"
        }
        if (previous.essays > 0 && current.essays == 0) {
            return "以前のバックアップにはエッセイがありますが、端末側のエッセイが0件です。"
        }
        if (previous.wishLists > 0 && current.wishLists == 0) {
            return "以前のバックアップには「やりたい事リスト」がありますが、端末側では0件です。"
        }
        if (previous.images > 0 && current.images == 0) {
            return "以前のバックアップには日記の写真がありますが、端末側では0件です。"
        }
        if (previous.totalRecords >= 5 && current.totalRecords.toLong() * 4 < previous.totalRecords.toLong()) {
            return "バックアップ対象の記録数が以前の4分の1未満に減っています。"
        }
        if (previous.images >= 5 && current.images.toLong() * 4 < previous.images.toLong()) {
            return "バックアップ対象の写真が以前の4分の1未満に減っています。"
        }
        if (previous.payloadBytes >= 4_096L && current.payloadBytes * 4 < previous.payloadBytes) {
            return "バックアップ対象のデータ量が以前の4分の1未満に減っています。"
        }
        return null
    }
}
