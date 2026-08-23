package com.example.kennys_dokidoki_wallpaper

/**
 * 画像を長く見続けてもヒープが膨らみ続けないよう、いま必要な枚だけ残す。
 */
object ImageMemoryPressurePolicy {
    enum class Band { CALM, WARM, HOT, CRITICAL }

    const val WORKING_SET = 5
    const val MAX_ORIGINALS_CALM = 5
    const val MAX_ORIGINALS_WARM = 3
    const val MAX_ORIGINALS_HOT = 1
    const val MAX_ORIGINALS_CRITICAL = 0

    const val MIN_ORIGINAL_BYTES = 16L * 1024 * 1024
    const val MAX_ORIGINAL_BYTES = 64L * 1024 * 1024

    const val GRID_BINDS_PER_CYCLE = 18
    const val CYCLE_MIN_INTERVAL_MS = 12_000L
    const val HEARTBEAT_MS = 10_000L

    const val GRID_THUMB_WIDTH = 280
    const val GRID_THUMB_HEIGHT = 498
    const val CARD_THUMB_WIDTH = 240
    const val CARD_THUMB_HEIGHT = 360

    const val GLIDE_MEMORY_MAX_BYTES = 24L * 1024 * 1024
    const val GLIDE_BITMAP_POOL_MAX_BYTES = 12L * 1024 * 1024
    const val GLIDE_DISK_MAX_BYTES = 64L * 1024 * 1024
    const val THUMB_DISK_MAX_BYTES = 80L * 1024 * 1024

    const val TRIM_RUNNING_MODERATE = 5
    const val TRIM_RUNNING_LOW = 10
    const val TRIM_RUNNING_CRITICAL = 15
    const val TRIM_UI_HIDDEN = 20
    const val TRIM_BACKGROUND = 40

    data class Slot(val key: String, val bytes: Int)

    fun originalBudgetBytes(maxHeap: Long): Long {
        if (maxHeap <= 0L) return MIN_ORIGINAL_BYTES
        return (maxHeap / 8L).coerceIn(MIN_ORIGINAL_BYTES, MAX_ORIGINAL_BYTES)
    }

    fun bandFromHeap(used: Long, maxHeap: Long): Band {
        if (maxHeap <= 0L) return Band.WARM
        val ratio = used.toDouble() / maxHeap.toDouble()
        return when {
            ratio >= 0.85 -> Band.CRITICAL
            ratio >= 0.72 -> Band.HOT
            ratio >= 0.58 -> Band.WARM
            else -> Band.CALM
        }
    }

    fun bandFromTrimLevel(level: Int): Band {
        return when {
            level >= TRIM_BACKGROUND -> Band.CRITICAL
            level >= TRIM_UI_HIDDEN -> Band.HOT
            level >= TRIM_RUNNING_CRITICAL -> Band.CRITICAL
            level >= TRIM_RUNNING_LOW -> Band.HOT
            level >= TRIM_RUNNING_MODERATE -> Band.WARM
            else -> Band.CALM
        }
    }

    fun worse(left: Band, right: Band): Band {
        return if (left.ordinal >= right.ordinal) left else right
    }

    fun originalLimit(band: Band): Int {
        return when (band) {
            Band.CALM -> MAX_ORIGINALS_CALM
            Band.WARM -> MAX_ORIGINALS_WARM
            Band.HOT -> MAX_ORIGINALS_HOT
            Band.CRITICAL -> MAX_ORIGINALS_CRITICAL
        }
    }

    fun shouldClearGlideMemory(band: Band): Boolean = band == Band.CRITICAL

    fun shouldTrimGlide(band: Band): Boolean = band != Band.CALM

    fun glideTrimLevel(band: Band): Int {
        return when (band) {
            Band.CRITICAL -> TRIM_RUNNING_CRITICAL
            Band.HOT -> TRIM_RUNNING_LOW
            Band.WARM -> TRIM_RUNNING_MODERATE
            Band.CALM -> 0
        }
    }

    fun shouldCancelDownloads(band: Band): Boolean = band == Band.CRITICAL

    fun shouldRunPeriodicCycle(bindsSinceLast: Int, elapsedMs: Long): Boolean {
        if (bindsSinceLast >= GRID_BINDS_PER_CYCLE) return true
        return elapsedMs >= CYCLE_MIN_INTERVAL_MS && bindsSinceLast > 0
    }

    fun shouldRunHeartbeat(elapsedMs: Long): Boolean = elapsedMs >= HEARTBEAT_MS

    fun shouldSweepDisk(band: Band): Boolean = band == Band.HOT || band == Band.CRITICAL

    fun shouldClearGlideDisk(band: Band): Boolean = band == Band.CRITICAL

    fun glideMemoryBytes(calculatorBytes: Long): Long =
        calculatorBytes.coerceAtMost(GLIDE_MEMORY_MAX_BYTES).coerceAtLeast(8L * 1024 * 1024)

    fun glideBitmapPoolBytes(calculatorBytes: Long): Long =
        calculatorBytes.coerceAtMost(GLIDE_BITMAP_POOL_MAX_BYTES).coerceAtLeast(4L * 1024 * 1024)

    fun keepKeys(current: String?, neighbors: Collection<String>): Set<String> {
        val keys = LinkedHashSet<String>()
        current?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        neighbors.forEach { key ->
            if (key.isNotBlank()) keys.add(key)
        }
        return keys
    }

    fun evictToBudget(
        lruOldestFirst: List<Slot>,
        keep: Set<String>,
        maxEntries: Int,
        maxBytes: Long
    ): List<Slot> {
        val remaining = if (keep.isEmpty()) {
            lruOldestFirst.toMutableList()
        } else {
            lruOldestFirst.filter { it.key in keep }.toMutableList()
        }
        val entryCap = maxEntries.coerceAtLeast(0)
        val byteCap = maxBytes.coerceAtLeast(0L)
        while (remaining.isNotEmpty()) {
            val total = remaining.fold(0L) { acc, slot -> acc + slot.bytes }
            if (remaining.size <= entryCap && total <= byteCap) break
            remaining.removeAt(0)
        }
        if (entryCap == 0) remaining.clear()
        return remaining
    }
}
