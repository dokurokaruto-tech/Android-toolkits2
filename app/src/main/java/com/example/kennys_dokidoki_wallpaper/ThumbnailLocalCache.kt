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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PCの圧縮サムネイルを端末へ保存し、カード／プリセットをオフラインでもすぐ出す。
 * 一斉ダウンロードでPCも端末も潰さないよう、同時取得は最大2本。
 */
object ThumbnailLocalCache {
    private const val TAG = "ThumbCache"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(ThumbnailLocalCachePolicy.MAX_IN_FLIGHT)
    private val inflight = mutableSetOf<String>()

    fun existingLocal(context: Context, target: ThumbnailBindPolicy.Target, remote: String): Uri? {
        if (!target.isValid || remote.isBlank()) return null
        val dest = File(
            File(context.filesDir, ThumbnailLocalCachePolicy.DIR_NAME),
            ThumbnailLocalCachePolicy.localFileName(target.kind, target.id, remote)
        )
        return if (dest.isFile && dest.length() > 0L) Uri.fromFile(dest) else null
    }

    /**
     * すでに端末へ落としたファイルがあれば、カード／プリセットのURIをそれに差し替える。
     * UI構築前に呼べば、一覧はネットワークへ走らない。
     */
    fun adoptExisting(context: Context): Int {
        val app = context.applicationContext
        val pending = currentPending()
        var changed = 0
        var cardsDirty = false
        var presetsDirty = false
        pending.forEach { (target, url) ->
            val local = existingLocal(app, target, url) ?: return@forEach
            when (ThumbnailBinder.replaceWithLocal(app, target, local, persist = false)) {
                ThumbnailBinder.ApplyResult.CARD -> {
                    cardsDirty = true
                    changed++
                }
                ThumbnailBinder.ApplyResult.PRESET -> {
                    presetsDirty = true
                    changed++
                }
                else -> Unit
            }
        }
        if (cardsDirty) PromptCardManager.saveCards(app)
        if (presetsDirty) PresetManager.savePresets(app)
        return changed
    }

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
                gate.withPermit {
                    val local = persist(app, target, remote) ?: return@withPermit
                    ThumbnailBinder.replaceWithLocal(app, target, local, persist = true)
                }
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
        currentPending().forEach { (target, url) ->
            enqueue(app, target, Uri.parse(url))
        }
    }

    private fun currentPending(): List<Pair<ThumbnailBindPolicy.Target, String>> =
        ThumbnailLocalCachePolicy.collectPending(
            PromptCardManager.promptCards.map { it.id to it.thumbnailUri?.toString() },
            PresetManager.presets.map { it.id to it.thumbnailUri?.toString() }
        )

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
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = ThumbnailLocalCachePolicy.decodeSampleSize(bounds.outWidth, bounds.outHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
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
            stream.toByteArray()
        } catch (error: OutOfMemoryError) {
            Log.w(TAG, "Could not recompress thumbnail", error)
            bytes
        }
    }
}
