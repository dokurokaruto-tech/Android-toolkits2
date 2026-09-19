package com.example.kennys_dokidoki_wallpaper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Civitai public REST API v1. Phone resolves metadata only;
// multi-GB model bytes are downloaded by the PC agent.
object CivitaiApi {
    const val KIND_CHECKPOINT = "checkpoint"
    const val KIND_LORA = "lora"
    private const val MAX_IMAGES = 30
    private const val MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024

    data class ModelFile(
        val id: Long,
        val name: String,
        val sizeKB: Double,
        val type: String,
        val format: String,
        val primary: Boolean,
        val downloadUrl: String
    )

    data class ShowImage(
        val url: String,
        val width: Int,
        val height: Int,
        val prompt: String,
        val negativePrompt: String
    )

    data class Version(
        val id: Long,
        val name: String,
        val baseModel: String,
        val trainedWords: List<String>,
        val files: List<ModelFile>,
        val images: List<ShowImage>
    )

    data class Model(
        val id: Long,
        val name: String,
        val type: String,
        val versions: List<Version>
    )

    suspend fun fetchModel(modelId: Long): Model = withContext(Dispatchers.IO) {
        val connection = (URL("https://civitai.com/api/v1/models/$modelId?nsfw=true").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AndroidToolkits/1.0")
            connectTimeout = 15000
            readTimeout = 30000
        }
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Civitai API HTTP $code")
            }
            parseModel(JSONObject(text))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadBytes(rawUrl: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "AndroidToolkits/1.0")
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("画像の取得に失敗 HTTP ${connection.responseCode}")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            connection.inputStream.use { stream ->
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) {
                        break
                    }
                    total += read
                    if (total > MAX_DOWNLOAD_BYTES) {
                        throw IOException("画像が大きすぎます")
                    }
                    out.write(buf, 0, read)
                }
            }
            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    fun parseModel(json: JSONObject): Model {
        val versions = mutableListOf<Version>()
        val rawVersions = json.optJSONArray("modelVersions")
        if (rawVersions != null) {
            for (i in 0 until rawVersions.length()) {
                versions.add(parseVersion(rawVersions.getJSONObject(i)))
            }
        }
        return Model(
            id = json.optLong("id"),
            name = json.optString("name", "名称未設定"),
            type = json.optString("type", ""),
            versions = versions
        )
    }

    private fun parseVersion(json: JSONObject): Version {
        val files = mutableListOf<ModelFile>()
        val versionId = json.optLong("id")
        val versionDownload = json.optString("downloadUrl")
        val rawFiles = json.optJSONArray("files")
        if (rawFiles != null) {
            for (i in 0 until rawFiles.length()) {
                val file = rawFiles.getJSONObject(i)
                files.add(
                    ModelFile(
                        id = file.optLong("id"),
                        name = file.optString("name"),
                        sizeKB = file.optDouble("sizeKB", 0.0),
                        type = file.optString("type", ""),
                        format = file.optJSONObject("metadata")?.optString("format").orEmpty(),
                        primary = file.optBoolean("primary", false),
                        downloadUrl = file.optString("downloadUrl").ifBlank {
                            versionDownload.ifBlank { "https://civitai.com/api/download/models/$versionId" }
                        }
                    )
                )
            }
        }
        val images = mutableListOf<ShowImage>()
        val rawImages = json.optJSONArray("images")
        if (rawImages != null) {
            for (i in 0 until minOf(rawImages.length(), MAX_IMAGES)) {
                val image = rawImages.getJSONObject(i)
                val meta = image.optJSONObject("meta")
                images.add(
                    ShowImage(
                        url = image.optString("url"),
                        width = image.optInt("width", 0),
                        height = image.optInt("height", 0),
                        prompt = meta?.optString("prompt").orEmpty(),
                        negativePrompt = meta?.optString("negativePrompt").orEmpty()
                    )
                )
            }
        }
        val words = mutableListOf<String>()
        val rawWords = json.optJSONArray("trainedWords")
        if (rawWords != null) {
            for (i in 0 until rawWords.length()) {
                rawWords.optString(i).trim().takeIf { it.isNotEmpty() }?.let { words.add(it) }
            }
        }
        return Version(
            id = json.optLong("id"),
            name = json.optString("name", "v?"),
            baseModel = json.optString("baseModel", ""),
            trainedWords = words,
            files = files,
            images = images
        )
    }

    fun kindFor(modelType: String): String {
        return if (modelType.equals("Checkpoint", ignoreCase = true)) KIND_CHECKPOINT else KIND_LORA
    }

    fun kindLabel(kind: String): String {
        return if (kind == KIND_CHECKPOINT) "Checkpoint" else "LoRA"
    }

    fun primaryFile(version: Version): ModelFile? {
        val models = version.files.filter { it.type.equals("Model", true) }
        return models.firstOrNull { it.primary }
            ?: models.firstOrNull { it.format.equals("SafeTensor", true) }
            ?: models.firstOrNull()
            ?: version.files.firstOrNull()
    }

    fun loraName(fileName: String): String {
        return fileName.substringBeforeLast(".").ifBlank { fileName }
    }

    // Summon tag first, then trigger words, then the creator example prompt.
    fun buildMainPrompt(kind: String, fileName: String, trained: List<String>, example: String): String {
        val head = buildList {
            if (kind == KIND_LORA) {
                add("<lora:${loraName(fileName)}:0.8>")
            }
            trained.filter { it.isNotBlank() }.forEach { add(it.trim()) }
        }.joinToString(", ")
        return listOf(head, example.trim()).filter { it.isNotEmpty() }.joinToString(", ")
    }

    fun formatSize(sizeKB: Double): String {
        if (sizeKB <= 0.0) {
            return "サイズ不明"
        }
        val mb = sizeKB / 1024.0
        if (mb < 1024.0) {
            return "%.1f MB".format(mb)
        }
        return "%.2f GB".format(mb / 1024.0)
    }
}
