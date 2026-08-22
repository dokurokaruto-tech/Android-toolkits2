package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-only encoded original image cache. It keeps at most 50 images and never writes
 * them to disk. KennysApplication clears it after the app is minimized.
 */
object OriginalImageMemoryCache {
    private const val MAX_ENTRIES = 50
    private const val MAX_SINGLE_IMAGE_BYTES = 128 * 1024 * 1024
    private val maxBytes: Long = (Runtime.getRuntime().maxMemory() / 3)
        .coerceIn(64L * 1024 * 1024, 384L * 1024 * 1024)

    private val lock = Any()
    private val entries = LinkedHashMap<String, ByteArray>(MAX_ENTRIES, 0.75f, true)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()
    private var totalBytes = 0L
    private var generation = 0L

    suspend fun getOrDownload(uri: Uri): ByteArray {
        val key = uri.toString()
        synchronized(lock) { entries[key]?.let { return it } }

        val mine = CompletableDeferred<ByteArray>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        val requestGeneration = synchronized(lock) { generation }
        try {
            val bytes = download(uri)
            synchronized(lock) {
                // A download finishing after minimization may be displayed by its caller,
                // but must not recreate the session cache that was just cleared.
                if (requestGeneration == generation) putLocked(key, bytes)
            }
            mine.complete(bytes)
            return bytes
        } catch (error: Throwable) {
            mine.completeExceptionally(error)
            throw error
        } finally {
            inFlight.remove(key, mine)
        }
    }

    suspend fun prefetch(uri: Uri) {
        if (!ImageStoragePolicy.isRemote(uri)) return
        runCatching { getOrDownload(uri) }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalBytes = 0L
            generation++
        }
    }

    fun entryCount(): Int = synchronized(lock) { entries.size }

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
