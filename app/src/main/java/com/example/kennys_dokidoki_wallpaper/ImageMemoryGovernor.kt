package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.os.SystemClock
import com.bumptech.glide.Glide

/**
 * 画像キャッシュを、見ている枚＋隣だけ残す周期で捨てる。
 */
object ImageMemoryGovernor {
    private val lock = Any()
    private var lastCycleMs = 0L
    private var bindsSinceCycle = 0
    private var lastKeep = emptySet<String>()

    fun noteWorkingSet(keys: Set<String>) {
        synchronized(lock) { lastKeep = keys }
    }

    fun onViewerWorkingSet(context: Context, keys: Set<String>) {
        noteWorkingSet(keys)
        apply(context, heapBand(), keys)
    }

    fun onGridBind(context: Context) {
        val now = SystemClock.uptimeMillis()
        val run = synchronized(lock) {
            bindsSinceCycle += 1
            val due = ImageMemoryPressurePolicy.shouldRunPeriodicCycle(
                bindsSinceCycle,
                now - lastCycleMs
            )
            if (due) {
                bindsSinceCycle = 0
                lastCycleMs = now
            }
            due
        }
        if (run) apply(context, heapBand(), currentKeep())
    }

    fun onTrimMemory(context: Context, level: Int) {
        val band = ImageMemoryPressurePolicy.worse(
            heapBand(),
            ImageMemoryPressurePolicy.bandFromTrimLevel(level)
        )
        apply(context, band, currentKeep())
    }

    fun onLowMemory(context: Context) {
        apply(context, ImageMemoryPressurePolicy.Band.CRITICAL, currentKeep())
    }

    private fun currentKeep(): Set<String> = synchronized(lock) { lastKeep }

    private fun heapBand(): ImageMemoryPressurePolicy.Band {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return ImageMemoryPressurePolicy.bandFromHeap(used, runtime.maxMemory())
    }

    private fun apply(
        context: Context,
        band: ImageMemoryPressurePolicy.Band,
        keep: Set<String>
    ) {
        val budget = ImageMemoryPressurePolicy.originalBudgetBytes(Runtime.getRuntime().maxMemory())
        OriginalImageMemoryCache.retainThenTrim(
            keep = keep,
            maxKept = ImageMemoryPressurePolicy.originalLimit(band),
            budgetBytes = budget
        )
        if (ImageMemoryPressurePolicy.shouldCancelDownloads(band)) {
            OriginalImageMemoryCache.clear()
        }
        val app = context.applicationContext
        try {
            if (ImageMemoryPressurePolicy.shouldClearGlideMemory(band)) {
                Glide.get(app).clearMemory()
            } else if (ImageMemoryPressurePolicy.shouldTrimGlide(band)) {
                Glide.get(app).trimMemory(ImageMemoryPressurePolicy.glideTrimLevel(band))
            }
        } catch (_: Exception) {
            // Glide がまだ起きていない起動直後は無視する。
        }
    }
}
