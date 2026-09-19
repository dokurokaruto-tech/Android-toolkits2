package com.example.kennys_dokidoki_wallpaper

import java.io.File

/**
 * 端末に残すサムネイルの保存先（本体／SD）と容量上限の決め方。
 * 対応するのは選択ロジックまで。File の実操作は呼び出し側に置く。
 */
object ThumbnailStoragePolicy {
    const val PREF_LOCATION = "thumbnail_cache_location"
    const val PREF_CAPACITY_BYTES = "thumbnail_cache_capacity_bytes"
    const val PREF_TOTAL_SAVED_BYTES = "thumbnail_total_saved_bytes"

    enum class Location { INTERNAL, SD_CARD }

    val DEFAULT_LOCATION = Location.INTERNAL
    val DEFAULT_CAPACITY_BYTES = ImageMemoryPressurePolicy.THUMB_DISK_MAX_BYTES

    /** 実質無制限。prune はこの値では一切消さない。 */
    const val UNLIMITED = Long.MAX_VALUE

    val CAPACITY_CHOICES: List<Long> = listOf(
        80L * 1024 * 1024,
        256L * 1024 * 1024,
        512L * 1024 * 1024,
        1024L * 1024 * 1024,
        2L * 1024 * 1024 * 1024,
        UNLIMITED
    )

    data class Candidate(val file: File, val isRemovable: Boolean)

    /** SD 選択時は取り外し可能な最初のアプリ専用領域へ。無ければ本体へ退避する。 */
    fun resolveDir(preferred: Location, internal: File, external: List<Candidate>): File {
        if (preferred == Location.SD_CARD) {
            external.firstOrNull { it.isRemovable }?.let { return it.file }
        }
        return internal
    }

    fun locationOf(raw: String?): Location =
        Location.values().firstOrNull { it.name == raw } ?: DEFAULT_LOCATION

    fun capacityBytes(raw: Long): Long = if (raw > 0) raw else DEFAULT_CAPACITY_BYTES

    fun label(bytes: Long): String {
        if (bytes >= UNLIMITED) return "無制限"
        val gb = 1024L * 1024 * 1024
        if (bytes >= gb && bytes % gb == 0L) return "${bytes / gb}GB"
        return "${bytes / (1024L * 1024)}MB"
    }

    /** 使用量や累計のような端数のある値の表示。 */
    fun usageLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2fGB".format(bytes / 1073741824f)
        else -> "%.1fMB".format(bytes / 1048576f)
    }
}
