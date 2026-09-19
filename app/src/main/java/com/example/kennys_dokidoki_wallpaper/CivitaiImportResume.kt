package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

// One pending Civitai import, persisted so killing the app never loses it.
// PC execution is independent; this only polls to terminal and builds the card.
data class PendingCivitaiImport(
    val jobId: String,
    val modelId: Long,
    val versionId: Long,
    val filename: String,
    val kind: String,
    val label: String,
    val mainPrompt: String,
    val negativePrompt: String,
    val category: String,
    val thumbPath: String?,
    val thumbUrl: String?
)

object CivitaiImportStore {
    private const val PREFS = "civitai_import_prefs"
    private const val KEY_PENDING = "pending_import"
    private const val KEY_LAST_ERROR = "last_import_error"

    fun savePending(context: Context, pending: PendingCivitaiImport) {
        val json = JSONObject().apply {
            put("jobId", pending.jobId)
            put("modelId", pending.modelId)
            put("versionId", pending.versionId)
            put("filename", pending.filename)
            put("kind", pending.kind)
            put("label", pending.label)
            put("mainPrompt", pending.mainPrompt)
            put("negativePrompt", pending.negativePrompt)
            put("category", pending.category)
            put("thumbPath", pending.thumbPath.orEmpty())
            put("thumbUrl", pending.thumbUrl.orEmpty())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PENDING, json.toString())
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun loadPending(context: Context): PendingCivitaiImport? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING, null) ?: return null
        return try {
            val json = JSONObject(raw)
            PendingCivitaiImport(
                jobId = json.getString("jobId"),
                modelId = json.optLong("modelId"),
                versionId = json.optLong("versionId"),
                filename = json.optString("filename"),
                kind = json.optString("kind"),
                label = json.optString("label"),
                mainPrompt = json.optString("mainPrompt"),
                negativePrompt = json.optString("negativePrompt"),
                category = json.optString("category", "未分類"),
                thumbPath = json.optString("thumbPath").takeIf { it.isNotBlank() },
                thumbUrl = json.optString("thumbUrl").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PENDING)
            .apply()
    }

    fun saveLastError(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ERROR, message.take(300))
            .apply()
    }

    // Reads the failure note once, so only the first observer toasts it.
    fun takeLastError(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_LAST_ERROR, null)
        if (message != null) {
            prefs.edit().remove(KEY_LAST_ERROR).apply()
        }
        return message
    }
}

object CivitaiImportFinalizer {
    private const val THUMB_MAX_SIDE = 768
    private val running = AtomicBoolean(false)

    // True = card created, false = terminal failure, null = owned elsewhere.
    // Transient errors throw with the pending entry kept for the next resume.
    suspend fun awaitAndFinalize(
        context: Context,
        pending: PendingCivitaiImport,
        onTick: ((ModelImportState) -> Unit)? = null
    ): Boolean? {
        if (!running.compareAndSet(false, true)) {
            return null
        }
        try {
            var state = GenerationAgentClient.getModelImport(context, pending.jobId)
            onTick?.invoke(state)
            while (!state.isTerminal) {
                delay(1500)
                state = GenerationAgentClient.getModelImport(context, pending.jobId)
                onTick?.invoke(state)
            }
            if (!state.isOk) {
                fail(context, state.error ?: "PCでの保存に失敗しました")
                return false
            }
            createCard(context, pending)
            CivitaiImportStore.clearPending(context)
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isGone(error)) {
                fail(context, "PC側にジョブがありません（エージェント再起動？）")
                return false
            }
            throw error
        } finally {
            running.set(false)
        }
    }

    private fun isGone(error: Exception): Boolean {
        return error.message?.contains("HTTP 404") == true
    }

    private fun fail(context: Context, message: String) {
        CivitaiImportStore.clearPending(context)
        CivitaiImportStore.saveLastError(context, message)
    }

    private suspend fun createCard(context: Context, pending: PendingCivitaiImport) {
        val cardId = UUID.randomUUID().toString()
        val thumb = withContext(Dispatchers.IO) { saveThumb(context, cardId, pending) }
        val card = PromptCard(
            id = cardId,
            label = pending.label,
            mainPrompt = pending.mainPrompt,
            negativePrompt = pending.negativePrompt,
            thumbnailUri = thumb,
            category = pending.category.ifBlank { "未分類" }
        )
        PromptCardManager.addCard(context, card)
    }

    private fun saveThumb(context: Context, cardId: String, pending: PendingCivitaiImport): Uri? {
        val bytes: ByteArray = try {
            val cropped = pending.thumbPath?.let { File(it) }?.takeIf { it.isFile }
            when {
                cropped != null -> cropped.readBytes()
                !pending.thumbUrl.isNullOrBlank() -> download(pending.thumbUrl)
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > THUMB_MAX_SIDE * 2 && sample < 16) {
                sample *= 2
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
            }) ?: return null
            val scaled = scaleDown(decoded)
            val dir = File(context.filesDir, "civitai_thumbs").apply { mkdirs() }
            val out = File(dir, "$cardId.jpg")
            FileOutputStream(out).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            }
            if (scaled !== decoded) {
                decoded.recycle()
            }
            Uri.fromFile(out)
        } catch (_: Exception) {
            null
        }
    }

    private fun download(rawUrl: String): ByteArray {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidToolkits/1.0")
            connectTimeout = 15000
            readTimeout = 60000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= THUMB_MAX_SIDE) {
            return source
        }
        val ratio = THUMB_MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }
}
