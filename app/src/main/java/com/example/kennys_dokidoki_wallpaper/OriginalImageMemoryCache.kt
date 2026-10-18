package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

/** 原寸はディスクを正本にする。画面を離れても取得を継続し、RAMだけを解放する。 */
object OriginalImageMemoryCache {
    private lateinit var disk: OriginalDiskCache
    private val transfers = Semaphore(2)

    fun initialize(context: Context) {
        disk = OriginalDiskCache(File(context.applicationContext.cacheDir, "original_images"))
    }

    fun getFileIfPresent(uri: Uri): File? = disk.existing(uri.toString())

    suspend fun getFile(uri: Uri): File = requestOwnedDownload(uri, OriginalDiskCache.key(uri.toString())).await()

    private val maxEntries: Int = ImageMemoryPressurePolicy.MAX_ORIGINALS_CALM
    private val maxBytes: Long = ImageMemoryPressurePolicy.originalBudgetBytes(Runtime.getRuntime().maxMemory())

    private val lock = Any()
    private val entries = LinkedHashMap<String, ByteArray>(maxEntries, 0.75f, true)
    private val inFlight = mutableMapOf<String, CompletableDeferred<File>>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var totalBytes = 0L
    private var generation = 0L

    fun getIfPresent(uri: Uri): ByteArray? = synchronized(lock) { entries[uri.toString()] }

    suspend fun getOrDownload(uri: Uri): ByteArray {
        val key = uri.toString()
        synchronized(lock) { entries[key] }?.let { return it }
        val epoch = synchronized(lock) { generation }
        val file = getFile(uri)
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        synchronized(lock) {
            if (epoch == generation) {
                putLocked(key, bytes)
            }
        }
        return bytes
    }

    /** Starts or joins a cache-owned transfer which survives cancellation of this caller. */
    private fun requestOwnedDownload(uri: Uri, key: String): CompletableDeferred<File> = synchronized(lock) {
        inFlight[key]?.let { return@synchronized it }

        val deferred = CompletableDeferred<File>()
        inFlight[key] = deferred
        lateinit var job: Job
        job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                val file = transfers.withPermit {
                    disk.existing(uri.toString()) ?: download(uri)
                }
                deferred.complete(file)
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            } finally {
                synchronized(lock) {
                    if (inFlight[key] === deferred) inFlight.remove(key)
                    if (downloadJobs[key] === job) downloadJobs.remove(key)
                }
            }
        }
        downloadJobs[key] = job
        job.start()
        deferred
    }

    suspend fun prefetch(uri: Uri) {
        if (!ImageStoragePolicy.isRemote(uri)) return
        try {
            getFile(uri)
        } catch (error: CancellationException) {
            // The awaiting page plan was canceled, but the cache-owned transfer continues.
            throw error
        } catch (_: Exception) {
            // A failed speculative request must not affect the currently viewed image.
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalBytes = 0L
            generation++
        }
    }

    /**
     * 進行中のダウンロードを全部止める。背面で投機転送を続ける理由はない。
     * job の finally が inFlight/downloadJobs を掃除するので、ここは cancel だけ。
     */
    fun cancelTransfers() {
        val jobs = synchronized(lock) { downloadJobs.values.toList() }
        jobs.forEach { it.cancel() }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }
    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }
    fun byteSize(): Long = synchronized(lock) { totalBytes }

    fun retainThenTrim(keep: Set<String>, maxKept: Int = maxEntries, budgetBytes: Long = maxBytes) {
        synchronized(lock) {
            evictLocked(keep, maxKept, budgetBytes)
        }
    }

    private fun putLocked(key: String, bytes: ByteArray) {
        entries.remove(key)?.let { totalBytes -= it.size }
        entries[key] = bytes
        totalBytes += bytes.size
        evictLocked(emptySet(), maxEntries, maxBytes)
    }

    private fun evictLocked(keep: Set<String>, maxKept: Int, budgetBytes: Long) {
        val snapshot = entries.map { ImageMemoryPressurePolicy.Slot(it.key, it.value.size) }
        val remaining = ImageMemoryPressurePolicy.evictToBudget(
            lruOldestFirst = snapshot,
            keep = keep,
            maxEntries = maxKept,
            maxBytes = budgetBytes
        ).map { it.key }.toSet()
        entries.keys.filter { it !in remaining }.forEach { key ->
            entries.remove(key)?.let { totalBytes -= it.size }
        }
        if (totalBytes < 0L) totalBytes = 0L
    }

    private suspend fun download(uri: Uri): File = withContext(Dispatchers.IO) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Original image HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                disk.store(uri.toString(), input, connection.contentLengthLong)
            }
        } finally {
            connection.disconnect()
        }
    }
}
