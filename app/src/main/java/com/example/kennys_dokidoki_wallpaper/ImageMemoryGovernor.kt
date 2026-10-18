package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * RAM上の画像だけを、見ている枚＋隣に絞る。ディスクは保持する。
 * 心拍はアプリを開いている間だけ回す。閉じている間は何も動かさない。
 */
object ImageMemoryGovernor {
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false
    private var heartbeatActive = false
    private var lastCycleMs = 0L
    private var lastHeartbeatMs = 0L
    private var bindsSinceCycle = 0
    private var lastKeep = emptySet<String>()
    private var appContext: Context? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            if (!heartbeatActive) {
                return
            }
            onHeartbeat(context)
            handler.postDelayed(this, ImageMemoryPressurePolicy.HEARTBEAT_MS)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (installed) return
            installed = true
        }
    }

    /** アプリが前面へ戻った。心拍だけ再開し、キャッシュは次に使う時に絞る。 */
    fun onAppWaking(context: Context) {
        start(context)
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            lastHeartbeatMs = now
            lastCycleMs = now
            heartbeatActive = true
        }
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, ImageMemoryPressurePolicy.HEARTBEAT_MS)
    }

    /** アプリが背面へ行った。心拍を止め、RAM上の画像は全部捨てる。 */
    fun onAppSleeping(context: Context) {
        synchronized(lock) {
            heartbeatActive = false
        }
        handler.removeCallbacks(heartbeat)
        OriginalImageMemoryCache.cancelTransfers()
        OriginalImageMemoryCache.clear()
        try {
            Glide.get(context.applicationContext).clearMemory()
        } catch (_: Exception) {
            // Glide がまだ起きていない起動直後は無視する。
        }
    }

    fun noteWorkingSet(keys: Set<String>) {
        synchronized(lock) { lastKeep = keys }
    }

    fun onViewerWorkingSet(context: Context, keys: Set<String>) {
        noteWorkingSet(keys)
        apply(context, heapBand(), keys, sweepDisk = false)
    }

    fun onGridBind(context: Context) {
        start(context)
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
        if (run) apply(context, heapBand(), currentKeep(), sweepDisk = true)
    }

    fun onHeartbeat(context: Context) {
        val now = SystemClock.uptimeMillis()
        val run = synchronized(lock) {
            if (!ImageMemoryPressurePolicy.shouldRunHeartbeat(now - lastHeartbeatMs)) return
            lastHeartbeatMs = now
            true
        }
        if (run) apply(context, heapBand(), currentKeep(), sweepDisk = true)
    }

    fun onTrimMemory(context: Context, level: Int) {
        val band = ImageMemoryPressurePolicy.worse(
            heapBand(),
            ImageMemoryPressurePolicy.bandFromTrimLevel(level)
        )
        apply(context, band, currentKeep(), sweepDisk = ImageMemoryPressurePolicy.shouldSweepDisk(band))
    }

    fun onLowMemory(context: Context) {
        apply(context, ImageMemoryPressurePolicy.Band.CRITICAL, currentKeep(), sweepDisk = true)
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
        keep: Set<String>,
        sweepDisk: Boolean
    ) {
        val budget = ImageMemoryPressurePolicy.originalBudgetBytes(Runtime.getRuntime().maxMemory())
        OriginalImageMemoryCache.retainThenTrim(
            keep = keep,
            maxKept = ImageMemoryPressurePolicy.originalLimit(band),
            budgetBytes = budget
        )
        if (ImageMemoryPressurePolicy.shouldClearGlideMemory(band)) {
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
        if (sweepDisk) {
            io.launch {
                ThumbnailLocalCache.prune(app)
            }
        }
    }
}
