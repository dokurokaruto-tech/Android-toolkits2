package com.example.kennys_dokidoki_wallpaper

import java.time.Instant
import java.time.format.DateTimeParseException

/** Drive が返す RFC3339 の時刻（2026-09-30T12:34:56.000Z）を扱う */
object DriveTimes {

    fun toEpochMs(rfc3339: String?): Long {
        if (rfc3339.isNullOrBlank()) {
            return 0L
        }
        return try {
            Instant.parse(rfc3339).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
