package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlin.coroutines.coroutineContext

/**
 * Session-only encoded original image cache. It keeps at most 50 images and never writes
 * them to disk. Downloads are owned by this cache, not by a viewer Activity: turning a
 * page or closing the viewer stops waiting but the transfer finishes in the background.
 */
object OriginalImageMemoryCache {
    private const val MAX_ENTRIES = 50
    private const val MAX_SINGLE_IMAGE_BYTES = 128 * 1024 * 1024
    private val maxBytes: Long = (Runtime.getRuntime().maxMemory() / 3)
        .coerceIn(64L * 1024 * 1024, 384L * 1024 * 1024)

    private val lock = Any()
    private val entries = LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true)
    private val inFlight = mutableMapOf<String, CompletableDeferred<ByteArray>>()
    private val downloadJobs = mutableMapOf<String, Job>()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var totalBytes = 0L
    private var generation = 0L

    fun getIfPresent(uri: Uri): ByteArray? = synchronized(lock) { entries[uri.toString()] }

    suspend fun getOrDownload(uri: Uri): ByteArray {
        val key = uri.toString()
        synchronized(lock) { entries[key] }?.let { return it }
        return requestOwnedDownload(uri, key).await()
    }

    /** Starts or joins a cache-owned transfer which survives cancellation of this caller. */
    private fun requestOwnedDownload(uri: Uri, key: String): CompletableDeferred<ByteArray> = synchronized(lock) {
        entries[key]?.let { cached ->
            return@synchronized CompletableDeferred<ByteArray>().also { it.complete(cached) }
        }
        inFlight[key]?.let { return@synchronized it }

        val deferred = CompletableDeferred<ByteArray>()
        val requestGeneration = generation
        inFlight[key] = deferred
        lateinit var job: Job
        job = downloadScope.launch(start = CoroutineStart.LAZY) {
            try {
                val bytes = download(uri)
                synchronized(lock) {
                    // Minimize/low-memory clear is the only event allowed to discard a running transfer.
                    if (requestGeneration == generation) putLocked(key, bytes)
                }
                deferred.complete(bytes)
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
            getOrDownload(uri)
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
            // App minimization/low-memory is an explicit session boundary, so unlike a page
            // change it is allowed to stop transfers and discard partial bytes.
            downloadJobs.values.toList().forEach { it.cancel() }
            downloadJobs.clear()
            inFlight.values.toList().forEach { it.cancel() }
            inFlight.clear()
        }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }
    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }

    private fun putLocked(key: String, bytes: ByteArray) {
        entries.remove(key)?.let { totalBytes -= it.size }
        entries[key] = bytes
        totalBytes += bytes.size
        val iterator = entries.entries.iterator()
        while ((entries.size > MAX_ENTRIES || totalBytes > maxBytes) && entries.size > 1 && iterator.hasNext()) {
            val eldest = iterator.next()
            totalBytes -= eldest.value.size
            iterator.remove()
        }
    }

    private suspend fun download(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Original image HTTP ${connection.responseCode}")
            }
            val declared = connection.contentLengthLong
            if (declared > MAX_SINGLE_IMAGE_BYTES) throw IOException("画像が大きすぎます")
            val initialSize = if (declared >= 1L && declared <= Int.MAX_VALUE.toLong()) declared.toInt() else 64 * 1024
            val output = ByteArrayOutputStream(initialSize)
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SINGLE_IMAGE_BYTES) throw IOException("画像が大きすぎます")
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }
}
