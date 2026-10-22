package com.example.kennys_dokidoki_wallpaper

import java.time.LocalDate

internal data class DiaryBackupCounts(
    val diaries: Int,
    val retiredRecords: Int,
    val images: Int,
    val payloadBytes: Long = 0L
) {
    val totalRecords: Int get() = diaries + retiredRecords
}

/** ZIP内の日記・画像パスを検証し、古いアーカイブ項目は読み込まずに数える。 */
internal object DiaryBackupPolicy {

    private const val DIARY_PREFIX = "diary/"
    private const val RETIRED_PREFIX = "notes/"
    private const val IMAGES_PREFIX = "images/"
    private const val JSON_SUFFIX = ".json"
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val safeImageName = Regex("[A-Za-z0-9_-]{1,128}\\.img")
    private val safeRetiredName = Regex("[A-Za-z0-9_-]{1,128}\\.json")

    fun diaryDateOf(path: String): String? {
        if (!path.startsWith(DIARY_PREFIX) || !path.endsWith(JSON_SUFFIX)) return null
        val date = path.removePrefix(DIARY_PREFIX).removeSuffix(JSON_SUFFIX)
        if (!datePattern.matches(date)) return null
        return runCatching { LocalDate.parse(date).takeIf { it.toString() == date }?.let { date } }
            .getOrNull()
    }

    /** 過去のバージョンの未対応データ。復元・書き出しはせず、上書き保護にのみ使う。 */
    fun isRetiredRecordPath(path: String): Boolean {
        if (!path.startsWith(RETIRED_PREFIX) || !path.endsWith(JSON_SUFFIX)) return false
        val parts = path.removePrefix(RETIRED_PREFIX).split('/')
        return parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9_-]{1,32}")) &&
            safeRetiredName.matches(parts[1])
    }

    fun imageNameOf(path: String): String? {
        if (!path.startsWith(IMAGES_PREFIX)) return null
        val name = path.removePrefix(IMAGES_PREFIX)
        return name.takeIf { safeImageName.matches(it) }
    }

    /** Manifestにはファイルの正確な更新時刻を記録するため、丸め誤差なしで比較する。 */
    fun shouldImport(localModifiedMs: Long, backupModifiedMs: Long): Boolean =
        localModifiedMs == 0L || backupModifiedMs > localModifiedMs
}

/** 端末データが急減したとき、最後の正常なバックアップを誤って空にしないための保護。 */
internal object DiaryBackupSafetyPolicy {

    fun overwriteWarning(previous: DiaryBackupCounts?, current: DiaryBackupCounts): String? {
        if (previous == null) return null
        if (previous.diaries > 0 && current.diaries == 0) {
            return "以前のバックアップには日記がありますが、端末側の日記が0件です。"
        }
        if (previous.retiredRecords > current.retiredRecords) {
            return "以前のバックアップには現在のアプリで扱わない旧データが含まれています。"
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
