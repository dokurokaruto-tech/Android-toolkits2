package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * サムネイル生成ジョブと「どのカード／プリセットへ載せるか」の対応。
 * UIが消えても、完成URLから同じ対象へ結び直せる。
 */
object ThumbnailBindPolicy {
    const val KIND_CARD = "card"
    const val KIND_PRESET = "preset"
    const val JOB_KIND_THUMBNAIL = "thumbnail"
    const val JOB_KIND_IMAGE = "image"

    data class Target(val kind: String, val id: String) {
        val isValid: Boolean
            get() = id.isNotBlank() && (kind == KIND_CARD || kind == KIND_PRESET)

        companion object {
            fun card(id: String) = Target(KIND_CARD, id.trim())
            fun preset(id: String) = Target(KIND_PRESET, id.trim())
        }
    }

    data class Item(
        val target: Target,
        val request: AgentGenerationRequest
    )

    fun pendingKey(target: Target): String = "${target.kind}:${target.id}"

    fun parseTarget(kind: String?, id: String?): Target? {
        val target = Target((kind ?: "").trim(), (id ?: "").trim())
        return target.takeIf { it.isValid }
    }

    fun encodeTargets(targets: Collection<Target>): String {
        val array = JSONArray()
        targets.forEach { target ->
            if (!target.isValid) return@forEach
            array.put(JSONObject().apply {
                put("kind", target.kind)
                put("id", target.id)
            })
        }
        return array.toString()
    }

    fun decodeTargets(raw: String?): List<Target> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    parseTarget(item.optString("kind"), item.optString("id"))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodePending(uris: Map<String, String>): String {
        val obj = JSONObject()
        uris.forEach { (key, uri) ->
            val trimmedKey = key.trim()
            val trimmedUri = uri.trim()
            if (trimmedKey.isNotEmpty() && trimmedUri.isNotEmpty()) obj.put(trimmedKey, trimmedUri)
        }
        return obj.toString()
    }

    fun decodePending(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val uri = obj.optString(key).trim()
                    if (key.isNotBlank() && uri.isNotEmpty()) put(key, uri)
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun pairUrls(urls: List<String>, targets: List<Target>): List<Pair<Target, String>> {
        if (targets.isEmpty()) return emptyList()
        return urls.mapIndexedNotNull { order, url ->
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return@mapIndexedNotNull null
            val index = GeneratedImageTagBinding.taskIndexFromUrl(trimmed)?.minus(1) ?: order
            val target = targets.getOrNull(index) ?: return@mapIndexedNotNull null
            target to trimmed
        }
    }
}
