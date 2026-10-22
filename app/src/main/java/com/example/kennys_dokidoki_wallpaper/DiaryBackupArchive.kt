package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 日記本文と参照写真だけを1つのZIPファイルにまとめる。 */
internal object DiaryBackupArchive {

    const val MIME_TYPE = "application/zip"
    const val SUGGESTED_FILE_NAME = "DiaryBackup.zip"

    private const val FORMAT = "kennys-diary-library-backup"
    private const val VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val MAX_ENTRIES = 50_000
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_JSON_BYTES = 16L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 2L * 1024 * 1024 * 1024

    data class MergeResult(
        val importedDiaries: Int,
        val importedImages: Int,
        val retiredRecords: Int
    )

    private data class SourceFile(
        val path: String,
        val file: File,
        val modifiedMs: Long
    )

    /** `output` の所有権は呼び出し元に残す。 */
    fun write(context: Context, output: OutputStream): DiaryBackupCounts {
        val sources = collectSources(context)
        validateSources(sources)
        val counts = countsFor(sources)
        val files = JSONArray()
        sources.forEach { source ->
            files.put(
                JSONObject()
                    .put("path", source.path)
                    .put("modifiedAt", source.modifiedMs)
            )
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("files", files)

        val zip = ZipOutputStream(output)
        putBytes(zip, MANIFEST_ENTRY, manifest.toString().toByteArray(StandardCharsets.UTF_8), System.currentTimeMillis())
        sources.forEach { source ->
            if (source.file.isFile) {
                zip.putNextEntry(ZipEntry(source.path).apply { time = source.modifiedMs })
                source.file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        zip.finish()
        zip.flush()
        return counts
    }

    /** 既存データは更新日時でマージし、ローカルの方が新しければ保持する。 */
    fun merge(context: Context, input: InputStream): MergeResult {
        val zip = ZipInputStream(input)
        val first = zip.nextEntry ?: throw IOException("バックアップファイルが空です。")
        if (first.name != MANIFEST_ENTRY) {
            throw IOException("このファイルは日記バックアップではありません。")
        }
        val counter = Counter()
        val manifestBytes = readEntry(zip, MAX_MANIFEST_BYTES, counter)
        zip.closeEntry()
        val importedModifiedTimes = parseManifest(manifestBytes)
        val stagingDirectory = File(context.cacheDir, "diary-backup-import-${java.util.UUID.randomUUID()}")
        if (!stagingDirectory.mkdirs()) {
            throw IOException("バックアップを一時保存する領域を作成できませんでした。")
        }

        try {
            val stagedDiaries = LinkedHashMap<String, StagedJson>()
            val stagedImages = LinkedHashMap<String, File>()
            var retiredRecords = 0
            var entryCount = 1

            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRIES) {
                    throw IOException("バックアップ内のファイル数が上限を超えています。")
                }
                if (entry.isDirectory) {
                    drainEntry(zip, counter, MAX_IMAGE_BYTES)
                    zip.closeEntry()
                    continue
                }

                val backupModifiedMs = importedModifiedTimes[entry.name] ?: entry.time
                val diaryDate = DiaryBackupPolicy.diaryDateOf(entry.name)
                val isRetiredRecord = DiaryBackupPolicy.isRetiredRecordPath(entry.name)
                val imageName = DiaryBackupPolicy.imageNameOf(entry.name)

                when {
                    diaryDate != null -> {
                        val bytes = readEntry(zip, MAX_JSON_BYTES, counter)
                        zip.closeEntry()
                        val raw = bytes.toString(StandardCharsets.UTF_8)
                        val requiredImages = safeDiaryImages(diaryDate, raw)
                        if (requiredImages != null) {
                            val stagedFile = File(File(stagingDirectory, "diary"), "$diaryDate.json")
                            writeStagedFile(stagedFile, bytes)
                            stagedDiaries[diaryDate] = StagedJson(stagedFile, backupModifiedMs, requiredImages)
                        }
                    }

                    isRetiredRecord -> {
                        drainEntry(zip, counter, MAX_JSON_BYTES)
                        zip.closeEntry()
                        retiredRecords += 1
                    }

                    imageName != null -> {
                        val target = DiaryStore.imageFile(context, imageName)
                        if (target.isFile) {
                            drainEntry(zip, counter, MAX_IMAGE_BYTES)
                        } else {
                            val stagedFile = File(File(stagingDirectory, "images"), imageName)
                            stagedFile.parentFile?.mkdirs()
                            stagedFile.outputStream().use { destination ->
                                copyEntry(zip, destination, counter, MAX_IMAGE_BYTES)
                            }
                            stagedImages[imageName] = stagedFile
                        }
                        zip.closeEntry()
                    }

                    else -> {
                        drainEntry(zip, counter, MAX_IMAGE_BYTES)
                        zip.closeEntry()
                    }
                }
            }

            // 先にすべてのZIP項目を検証・展開し、壊れたアーカイブで既存記録を途中変更しない。
            var importedImages = 0
            stagedImages.forEach { (imageName, stagedFile) ->
                val target = DiaryStore.imageFile(context, imageName)
                if (!target.isFile) {
                    target.parentFile?.mkdirs()
                    try {
                        stagedFile.inputStream().use { source ->
                            target.outputStream().use { destination -> source.copyTo(destination) }
                        }
                        importedImages += 1
                    } catch (error: Exception) {
                        target.delete()
                        throw error
                    }
                }
            }

            var importedDiaries = 0
            stagedDiaries.forEach { (date, staged) ->
                val imagesAvailable = staged.requiredImages.all { imageName ->
                    DiaryStore.imageFile(context, imageName).isFile
                }
                if (imagesAvailable && DiaryBackupPolicy.shouldImport(
                        DiaryStore.entryModifiedMs(context, date),
                        staged.modifiedMs
                    )
                ) {
                    DiaryStore.writeRaw(context, date, staged.file.readText(StandardCharsets.UTF_8))
                    importedDiaries += 1
                }
            }

            return MergeResult(importedDiaries, importedImages, retiredRecords)
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private data class StagedJson(
        val file: File,
        val modifiedMs: Long,
        val requiredImages: Set<String> = emptySet()
    )

    private fun writeStagedFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.outputStream().use { it.write(bytes) }
    }

    private fun countsFor(sources: List<SourceFile>) = DiaryBackupCounts(
        diaries = sources.count { it.path.startsWith("diary/") },
        retiredRecords = 0,
        images = sources.count { it.path.startsWith("images/") },
        payloadBytes = sources.fold(0L) { total, source -> total + source.file.length() }
    )

    private fun validateSources(sources: List<SourceFile>) {
        if (sources.size + 1 > MAX_ENTRIES) {
            throw IOException("バックアップに含まれるファイル数が上限を超えています。")
        }
        var totalBytes = 0L
        sources.forEach { source ->
            val length = source.file.length()
            val limit = if (source.path.startsWith("images/")) MAX_IMAGE_BYTES else MAX_JSON_BYTES
            if (length > limit) {
                throw IOException("バックアップ対象のファイルが大きすぎます: ${source.file.name}")
            }
            totalBytes += length
            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw IOException("バックアップ全体のデータが大きすぎます。")
            }
        }
    }

    private fun collectSources(context: Context): List<SourceFile> {
        val sources = mutableListOf<SourceFile>()
        val referencedImages = LinkedHashSet<String>()

        DiaryStore.savedDates(context).forEach { date ->
            if (DiaryBackupPolicy.diaryDateOf("diary/$date.json") == null) {
                return@forEach
            }
            val file = File(File(context.filesDir, "diary"), "$date.json")
            val raw = DiaryStore.readRaw(context, date) ?: return@forEach
            val imageNames = safeDiaryImages(date, raw) ?: return@forEach
            if (imageNames.any { !DiaryStore.imageFile(context, it).isFile }) return@forEach
            if (file.isFile) {
                sources += SourceFile("diary/$date.json", file, file.lastModified())
                referencedImages.addAll(imageNames)
            }
        }

        referencedImages.forEach { imageName ->
            val file = DiaryStore.imageFile(context, imageName)
            if (file.isFile) {
                sources += SourceFile("images/$imageName", file, file.lastModified())
            }
        }

        return sources
    }

    private fun parseManifest(bytes: ByteArray): Map<String, Long> {
        val manifest = try {
            JSONObject(bytes.toString(StandardCharsets.UTF_8))
        } catch (error: Exception) {
            throw IOException("バックアップの目次を読み取れません。", error)
        }
        if (manifest.optString("format") != FORMAT || manifest.optInt("version", -1) != VERSION) {
            throw IOException("このバックアップ形式には対応していません。")
        }
        val files = manifest.optJSONArray("files") ?: throw IOException("バックアップの目次がありません。")
        if (files.length() > MAX_ENTRIES) {
            throw IOException("バックアップの目次が大きすぎます。")
        }
        val modifiedTimes = LinkedHashMap<String, Long>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val path = file.optString("path")
            val modifiedAt = file.optLong("modifiedAt", -1L)
            if (modifiedAt >= 0L && isRecognizedPath(path)) {
                modifiedTimes[path] = modifiedAt
            }
        }
        return modifiedTimes
    }

    private fun isRecognizedPath(path: String): Boolean =
        DiaryBackupPolicy.diaryDateOf(path) != null ||
            DiaryBackupPolicy.imageNameOf(path) != null

    private fun safeDiaryImages(date: String, raw: String): Set<String>? {
        return try {
            val json = JSONObject(raw)
            if (!json.has("text") && !json.has("photos")) return null
            val imageNames = LinkedHashSet<String>()
            for (photo in DiaryJson.decode(date, raw).photos) {
                val imageName = DiaryBackupPolicy.imageNameOf("images/${photo.fileName}") ?: return null
                imageNames.add(imageName)
            }
            imageNames
        } catch (_: Exception) {
            null
        }
    }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray, modifiedMs: Long) {
        zip.putNextEntry(ZipEntry(path).apply { time = modifiedMs })
        zip.write(bytes)
        zip.closeEntry()
    }

    private class Counter(var bytes: Long = 0L)

    private fun readEntry(zip: ZipInputStream, limit: Long, counter: Counter): ByteArray {
        val output = ByteArrayOutputStream()
        copyEntry(zip, output, counter, limit)
        return output.toByteArray()
    }

    private fun drainEntry(zip: ZipInputStream, counter: Counter, limit: Long) {
        copyEntry(zip, DiscardingOutputStream, counter, limit)
    }

    private fun copyEntry(zip: ZipInputStream, output: OutputStream, counter: Counter, entryLimit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            entryBytes += count
            counter.bytes += count
            if (entryBytes > entryLimit || counter.bytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw IOException("バックアップ内のデータが大きすぎます。")
            }
            output.write(buffer, 0, count)
        }
    }

    private object DiscardingOutputStream : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
    }
}
