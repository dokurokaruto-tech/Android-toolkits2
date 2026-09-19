package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
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
            cacheDir(context),
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

    fun prune(context: Context) {
        val dir = cacheDir(context)
        if (!dir.isDirectory) return
        val listed = dir.listFiles() ?: return
        val keep = ThumbnailLocalCachePolicy.keepPrefixes(
            PromptCardManager.promptCards.map { it.id },
            PresetManager.presets.map { it.id }
        )
        val doomed = ThumbnailLocalCachePolicy.filesToDelete(
            listed.map { file ->
                ThumbnailLocalCachePolicy.DiskFile(file.name, file.length(), file.lastModified())
            },
            keep,
            capacityBytes(context)
        ).toSet()
        listed.forEach { file ->
            if (file.name in doomed) file.delete()
        }
    }

    fun enqueuePending(context: Context) {
        val app = context.applicationContext
        currentPending().forEach { (target, url) ->
            enqueue(app, target, Uri.parse(url))
        }
    }

    private fun settings(context: Context) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun preferredLocation(context: Context): ThumbnailStoragePolicy.Location =
        ThumbnailStoragePolicy.locationOf(
            settings(context).getString(ThumbnailStoragePolicy.PREF_LOCATION, null)
        )

    private fun externalCandidates(context: Context): List<ThumbnailStoragePolicy.Candidate> {
        val dirs = context.getExternalFilesDirs(null) ?: return emptyList()
        return dirs.filterNotNull().map { dir ->
            ThumbnailStoragePolicy.Candidate(
                File(dir, ThumbnailLocalCachePolicy.DIR_NAME),
                Environment.isExternalStorageRemovable(dir)
            )
        }
    }

    /** 現在の保存先。SD 選択でもカードが無ければ本体へ退避する。 */
    internal fun cacheDir(context: Context): File {
        val internal = File(context.filesDir, ThumbnailLocalCachePolicy.DIR_NAME)
        val chosen = ThumbnailStoragePolicy.resolveDir(
            preferredLocation(context),
            internal,
            externalCandidates(context)
        )
        return if (chosen.mkdirs() || chosen.isDirectory) chosen else internal.also { it.mkdirs() }
    }

    /** 設定ダイアログ用。挿されているSDカードのアプリ専用領域。 */
    fun sdCardDir(context: Context): File? =
        externalCandidates(context).firstOrNull { it.isRemovable }?.file

    private fun capacityBytes(context: Context): Long =
        ThumbnailStoragePolicy.capacityBytes(
            settings(context).getLong(ThumbnailStoragePolicy.PREF_CAPACITY_BYTES, 0L)
        )

    /**
     * 保存先を変えた直後に呼ぶ。旧場所の実ファイルを新場所へ移し、
     * カード／プリセットが張っている file:// を新場所へ付け直す。
     */
    fun relocate(context: Context) {
        val app = context.applicationContext
        val active = cacheDir(app)
        val internal = File(app.filesDir, ThumbnailLocalCachePolicy.DIR_NAME)
        val retired = (listOf(internal) + externalCandidates(app).map { it.file })
            .filter { it.absolutePath != active.absolutePath && it.isDirectory }
        retired.forEach { old -> moveAll(old, active) }
        rebaseBoundUris(app, active)
    }

    private fun moveAll(from: File, to: File) {
        val listed = from.listFiles() ?: return
        listed.forEach { file ->
            if (!file.isFile) return@forEach
            val target = File(to, file.name)
            when {
                target.isFile -> file.delete()
                file.renameTo(target) -> Unit
                else -> {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
            }
        }
        if (from.listFiles()?.isEmpty() == true) from.delete()
    }

    private fun rebaseBoundUris(context: Context, active: File) {
        val entries =
            PromptCardManager.promptCards.map { ThumbnailBindPolicy.Target.card(it.id) to it.thumbnailUri } +
                PresetManager.presets.map { ThumbnailBindPolicy.Target.preset(it.id) to it.thumbnailUri }
        var cardsDirty = false
        var presetsDirty = false
        entries.forEach { (target, uri) ->
            val name = uri?.lastPathSegment ?: return@forEach
            if (uri.scheme != "file") return@forEach
            val moved = File(active, name)
            if (!moved.isFile || uri.path == moved.absolutePath) return@forEach
            when (ThumbnailBinder.replaceWithLocal(context, target, Uri.fromFile(moved), persist = false)) {
                ThumbnailBinder.ApplyResult.CARD -> cardsDirty = true
                ThumbnailBinder.ApplyResult.PRESET -> presetsDirty = true
                else -> Unit
            }
        }
        if (cardsDirty) PromptCardManager.saveCards(context)
        if (presetsDirty) PresetManager.savePresets(context)
    }

    private fun currentPending(): List<Pair<ThumbnailBindPolicy.Target, String>> =
        ThumbnailLocalCachePolicy.collectPending(
            PromptCardManager.promptCards.map { it.id to it.thumbnailUri?.toString() },
            PresetManager.presets.map { it.id to it.thumbnailUri?.toString() }
        )

    internal fun persist(context: Context, target: ThumbnailBindPolicy.Target, remote: Uri): Uri? {
        val remoteText = remote.toString()
        val dir = cacheDir(context)
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
