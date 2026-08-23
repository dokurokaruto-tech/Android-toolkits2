package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/** 閲覧の日付フォルダを端末に残し、開いた瞬間から出す。 */
object GeneratedFolderCachePolicy {
    const val KEY = "generated_folder_cache"

    data class Entry(
        val date: String,
        val count: Int,
        val thumbnailUrl: String?
    )

    fun encode(entries: List<Entry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            if (entry.date.isBlank()) return@forEach
            array.put(JSONObject().apply {
                put("date", entry.date)
                put("count", entry.count.coerceAtLeast(0))
                put("thumbnailUrl", entry.thumbnailUrl ?: JSONObject.NULL)
            })
        }
        return array.toString()
    }

    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val date = item.optString("date").trim()
                    if (date.isEmpty()) continue
                    add(
                        Entry(
                            date = date,
                            count = item.optInt("count", 0).coerceAtLeast(0),
                            thumbnailUrl = item.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
