package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * 日記1日分。写真の位置と大きさは用紙に対する比率で持つので、
 * 画面の解像度が変わっても同じ見た目で復元できる。
 *
 *   xRatio 0.0 = 用紙の左端 / 1.0 = 右端
 *   widthRatio 0.5 = 用紙の横幅の半分
 */
data class DiaryPhoto(
    val fileName: String,
    val xRatio: Float,
    val yRatio: Float,
    val widthRatio: Float
)

data class DiaryEntry(
    val date: String,
    val text: String,
    val photos: List<DiaryPhoto>
) {
    fun isEmpty(): Boolean = text.isBlank() && photos.isEmpty()
}

/** 日記の保存形式。ファイルへの書き出しは DiaryStore が担う */
object DiaryJson {

    private const val KEY_TEXT = "text"
    private const val KEY_PHOTOS = "photos"
    private const val KEY_FILE = "file"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH = "w"

    fun encode(entry: DiaryEntry): String {
        val photos = JSONArray()
        entry.photos.forEach { photo ->
            photos.put(
                JSONObject()
                    .put(KEY_FILE, photo.fileName)
                    .put(KEY_X, photo.xRatio.toDouble())
                    .put(KEY_Y, photo.yRatio.toDouble())
                    .put(KEY_WIDTH, photo.widthRatio.toDouble())
            )
        }
        return JSONObject()
            .put(KEY_TEXT, entry.text)
            .put(KEY_PHOTOS, photos)
            .toString()
    }

    fun decode(date: String, raw: String?): DiaryEntry {
        if (raw.isNullOrBlank()) {
            return DiaryEntry(date, "", emptyList())
        }
        return try {
            val json = JSONObject(raw)
            val photos = json.optJSONArray(KEY_PHOTOS) ?: JSONArray()
            DiaryEntry(
                date = date,
                text = json.optString(KEY_TEXT, ""),
                photos = (0 until photos.length()).mapNotNull { index ->
                    photos.optJSONObject(index)?.let { item ->
                        DiaryPhoto(
                            fileName = item.optString(KEY_FILE),
                            xRatio = item.optDouble(KEY_X, 0.0).toFloat(),
                            yRatio = item.optDouble(KEY_Y, 0.0).toFloat(),
                            widthRatio = item.optDouble(KEY_WIDTH, 0.5).toFloat()
                        )
                    }
                }.filter { it.fileName.isNotBlank() }
            )
        } catch (_: Exception) {
            DiaryEntry(date, "", emptyList())
        }
    }
}
