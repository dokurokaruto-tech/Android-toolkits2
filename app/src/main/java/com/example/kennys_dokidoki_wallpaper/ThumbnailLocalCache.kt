package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PCの圧縮サムネイルを端末へ保存し、カード／プリセットをオフラインでもすぐ出す。
 */
object ThumbnailLocalCache {
    private const val TAG = "ThumbCache"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = mutableSetOf<String>()

    fun enqueue(context: Context, target: ThumbnailBindPolicy.Target, remote: Uri) {
        if (!target.isValid) return
        if (!ThumbnailLocalCachePolicy.needsLocalCopy(remote.toString())) return
        val key = ThumbnailBindPolicy.pendingKey(target)
        synchronized(inflight) {
            if (!inflight.add(key)) return
        }
        val app = context.applicationContext
        scope.launch {
            try {
                val local = persist(app, target, remote) ?: return@launch
                ThumbnailBinder.applyOne(app, target, local)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Could not persist thumbnail ${target.kind}:${target.id}", error)
            } finally {
                synchronized(inflight) { inflight.remove(key) }
            }
        }
    }

    fun enqueuePending(context: Context) {
        val app = context.applicationContext
        scope.launch {
            PromptCardManager.loadCards(app)
            PresetManager.loadPresets(app)
            ThumbnailLocalCachePolicy.collectPending(
                PromptCardManager.promptCards.map { it.id to it.thumbnailUri?.toString() },
                PresetManager.presets.map { it.id to it.thumbnailUri?.toString() }
            ).forEach { (target, url) ->
                enqueue(app, target, Uri.parse(url))
            }
        }
    }

    internal fun persist(context: Context, target: ThumbnailBindPolicy.Target, remote: Uri): Uri? {
        val remoteText = remote.toString()
        val dir = File(context.filesDir, ThumbnailLocalCachePolicy.DIR_NAME).also { it.mkdirs() }
        val dest = File(dir, ThumbnailLocalCachePolicy.localFileName(target.kind, target.id, remoteText))
        if (dest.isFile && dest.length() > 0L) {
            pruneOthers(dir, target, dest)
            return Uri.fromFile(dest)
        }
        val bytes = downloadBest(context, remoteText) ?: return null
        val encoded = encodeThumbnail(bytes)
        if (encoded.isEmpty()) return null
        val part = File(dir, dest.name + ".part")
        part.writeBytes(encoded)
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            dest.writeBytes(encoded)
            part.delete()
        }
        if (!dest.isFile || dest.length() <= 0L) return null
        pruneOthers(dir, target, dest)
        return Uri.fromFile(dest)
    }

    private fun pruneOthers(dir: File, target: ThumbnailBindPolicy.Target, keep: File) {
        val prefix = ThumbnailLocalCachePolicy.managedPrefix(target.kind, target.id)
        dir.listFiles()?.forEach { file ->
            if (file != keep && file.name.startsWith(prefix)) file.delete()
        }
    }

    private fun downloadBest(context: Context, remoteUrl: String): ByteArray? {
        ThumbnailLocalCachePolicy.downloadUrls(remoteUrl).forEach { candidate ->
            val bytes = download(GenerationAgentClient.absoluteUrl(context, candidate))
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun download(url: String): ByteArray? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 60000
            useCaches = false
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeThumbnail(bytes: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val alreadyJpeg = ThumbnailLocalCachePolicy.isJpeg(bytes)
        if (!ThumbnailLocalCachePolicy.shouldRecompress(bitmap.width, bitmap.height, alreadyJpeg)) {
            bitmap.recycle()
            return bytes
        }
        val (width, height) = ThumbnailLocalCachePolicy.scaledSize(bitmap.width, bitmap.height)
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, ThumbnailLocalCachePolicy.JPEG_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return stream.toByteArray()
    }
}
