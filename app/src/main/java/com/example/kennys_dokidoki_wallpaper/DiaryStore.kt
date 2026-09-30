package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * 日記の保管庫。1日1ファイルの JSON と、貼り付けた画像の実体を持つ。
 *
 *   files/diary/2026-09-30.json
 *   files/diary/images/<uuid>.img
 */
object DiaryStore {

    private const val DIR_NAME = "diary"
    private const val IMAGES_DIR_NAME = "images"
    private const val FILE_SUFFIX = ".json"
    private const val IMAGE_SUFFIX = ".img"

    /** 「2026-09-30」形式。ファイル名にも一覧の並びにもそのまま使える */
    fun dateKey(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

    fun todayKey(): String {
        val now = Calendar.getInstance()
        return dateKey(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
    }

    /** 「2026年9月30日」形式。画面の見出し用 */
    fun displayDate(dateKey: String): String {
        val parts = dateKey.split("-")
        if (parts.size != 3) {
            return dateKey
        }
        val year = parts[0].toIntOrNull() ?: return dateKey
        val month = parts[1].toIntOrNull() ?: return dateKey
        val day = parts[2].toIntOrNull() ?: return dateKey
        return "${year}年${month}月${day}日"
    }

    fun load(context: Context, dateKey: String): DiaryEntry =
        DiaryJson.decode(dateKey, AtomicFiles.readUtf8(entryFile(context, dateKey)))

    fun save(context: Context, entry: DiaryEntry) {
        val file = entryFile(context, entry.date)
        if (entry.isEmpty()) {
            file.delete()
            return
        }
        AtomicFiles.writeUtf8(file, DiaryJson.encode(entry))
    }

    fun hasEntry(context: Context, dateKey: String): Boolean = entryFile(context, dateKey).isFile

    /** 記録のある日付。新しい順 */
    fun savedDates(context: Context): List<String> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.map { it.name.removeSuffix(FILE_SUFFIX) }
            ?.sortedDescending()
            .orEmpty()

    /** 選んだ画像をアプリ内へ複製する。元の画像が消えても日記は崩れない */
    fun importImage(context: Context, source: InputStream): String? {
        val fileName = UUID.randomUUID().toString() + IMAGE_SUFFIX
        val target = File(imagesDir(context), fileName)
        return try {
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> source.copyTo(output) }
            fileName
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    fun imageFile(context: Context, fileName: String): File = File(imagesDir(context), fileName)

    /** どの日記からも参照されなくなった画像を片付ける */
    fun purgeUnusedImages(context: Context) {
        val used = savedDates(context)
            .flatMap { load(context, it).photos }
            .map { it.fileName }
            .toSet()
        imagesDir(context).listFiles()?.forEach { file ->
            if (file.name !in used) {
                file.delete()
            }
        }
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun imagesDir(context: Context): File = File(dir(context), IMAGES_DIR_NAME)

    private fun entryFile(context: Context, dateKey: String): File =
        File(dir(context), dateKey + FILE_SUFFIX)
}
