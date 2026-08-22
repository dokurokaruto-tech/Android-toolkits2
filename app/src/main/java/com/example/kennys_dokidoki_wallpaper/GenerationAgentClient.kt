package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** One image request persisted and executed by the PC generation agent. */
data class AgentGenerationRequest(
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val samplerName: String,
    val purpose: String = "image"
)

data class AgentGeneratedFolder(val date: String, val count: Int, val thumbnailUrl: String?)
data class AgentGeneratedImage(val name: String, val url: String, val createdAt: String)

data class AgentJobState(
    val id: String,
    val status: String,
    val total: Int,
    val completed: Int,
    val failed: Int,
    val progress: Float,
    val previewUrl: String?,
    val imageUrls: List<String>,
    val error: String?
) {
    val isTerminal: Boolean
        get() = status in setOf("completed", "partial_failed", "failed", "canceled")
}

/**
 * Client for pc-generation-agent. A submitted job lives on the PC, so closing Android
 * never cancels the requested image count. The last job id is persisted for reconnect.
 */
object GenerationAgentClient {
    private const val ACTIVE_JOB_KEY = "generation_agent_active_job_id"
    private const val TAG = "GenerationAgent"

    private fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun baseUrl(context: Context): String =
        (settings(context).getString("remote_server_url", "") ?: "").trim().removeSuffix("/")

    private fun apiKey(context: Context): String =
        (settings(context).getString("generation_agent_api_key", "") ?: "").trim()

    /** Builds a URL suitable for Glide too. Query auth is used because Glide cannot see our HTTP headers. */
    fun absoluteUrl(context: Context, pathOrUrl: String): String {
        val plain = if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            baseUrl(context) + if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        }
        val key = apiKey(context)
        if (key.isBlank() || Regex("[?&]token=").containsMatchIn(plain)) return plain
        val separator = if (plain.contains('?')) "&" else "?"
        return "$plain${separator}token=${URLEncoder.encode(key, "UTF-8")}"
    }

    fun hasPendingJob(context: Context): Boolean =
        !settings(context).getString(ACTIVE_JOB_KEY, null).isNullOrBlank()

    suspend fun isAvailable(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = requestJson(context, "/api/v1/health")
            json.optString("service") == "android-toolkits-generation-agent"
        } catch (_: Exception) {
            false
        }
    }

    suspend fun submit(
        context: Context,
        requests: List<AgentGenerationRequest>,
        persistForReconnect: Boolean = true
    ): AgentJobState = withContext(Dispatchers.IO) {
        require(requests.isNotEmpty()) { "生成リクエストが空です" }
        val tasks = JSONArray()
        requests.forEach { request ->
            tasks.put(JSONObject().apply {
                put("prompt", request.prompt)
                put("negative_prompt", request.negativePrompt)
                put("width", request.width)
                put("height", request.height)
                put("steps", request.steps)
                put("cfg_scale", 7)
                put("sampler_name", request.samplerName)
                put("purpose", request.purpose)
            })
        }
        val body = JSONObject().apply {
            put("client_request_id", UUID.randomUUID().toString())
            put("tasks", tasks)
        }
        val state = parseJob(context, requestJson(context, "/api/v1/jobs", "POST", body))
        if (persistForReconnect) {
            // Synchronous commit closes the tiny crash window between PC acceptance and local reconnect state.
            settings(context).edit().putString(ACTIVE_JOB_KEY, state.id).commit()
        }
        state
    }

    suspend fun getJob(context: Context, jobId: String): AgentJobState = withContext(Dispatchers.IO) {
        parseJob(context, requestJson(context, "/api/v1/jobs/$jobId"))
    }

    suspend fun fetchFolders(context: Context): List<AgentGeneratedFolder> = withContext(Dispatchers.IO) {
        val array = requestJson(context, "/api/v1/library/dates").optJSONArray("dates") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val thumbnail = item.optString("thumbnail_url").takeIf { it.isNotBlank() }
                add(AgentGeneratedFolder(item.getString("date"), item.optInt("count"), thumbnail?.let { absoluteUrl(context, it) }))
            }
        }
    }

    suspend fun fetchImages(context: Context, date: String): List<AgentGeneratedImage> = withContext(Dispatchers.IO) {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val array = requestJson(context, "/api/v1/library/images?date=$encodedDate").optJSONArray("images") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    AgentGeneratedImage(
                        name = item.getString("name"),
                        url = absoluteUrl(context, item.getString("url")),
                        createdAt = item.optString("created_at")
                    )
                )
            }
        }
    }

    suspend fun cancel(context: Context, jobId: String) {
        withContext(Dispatchers.IO) { requestJson(context, "/api/v1/jobs/$jobId/cancel", "POST", JSONObject()) }
    }

    suspend fun stopAfterCurrent(context: Context, jobId: String) {
        withContext(Dispatchers.IO) { requestJson(context, "/api/v1/jobs/$jobId/stop-after-current", "POST", JSONObject()) }
    }

    suspend fun skip(context: Context, jobId: String) {
        withContext(Dispatchers.IO) { requestJson(context, "/api/v1/jobs/$jobId/skip", "POST", JSONObject()) }
    }

    /** Monitors only while Android is alive. PC execution itself is independent of this loop. */
    suspend fun monitor(
        context: Context,
        initial: AgentJobState? = null,
        silent: Boolean = false,
        clearReconnectState: Boolean = true
    ): AgentJobState {
        val jobId = initial?.id ?: settings(context).getString(ACTIVE_JOB_KEY, null)
            ?: throw IllegalStateException("再接続する生成ジョブがありません")
        var state = initial ?: getJob(context, jobId)
        GenerationProgressManager.startGeneration(
            batchMode = !silent,
            total = state.total,
            silent = silent
        )
        var controlSent = ""
        var connectionFailures = 0
        try {
            while (!state.isTerminal) {
                GenerationProgressManager.updateBatchProgress(
                    (state.completed + 1).coerceAtMost(state.total), state.total
                )
                val preview = if (!state.previewUrl.isNullOrBlank()) {
                    downloadPreview(context, state.previewUrl)
                } else null
                GenerationProgressManager.updateState(
                    true,
                    state.progress,
                    preview ?: GenerationProgressManager.state.value.currentImage,
                    "PCで生成中... ${state.completed}/${state.total}"
                )

                try {
                    when {
                        GenerationProgressManager.shouldInterrupt && controlSent != "cancel" -> {
                            cancel(context, jobId)
                            controlSent = "cancel"
                        }
                        GenerationProgressManager.shouldStopGracefully && controlSent.isEmpty() -> {
                            stopAfterCurrent(context, jobId)
                            controlSent = "stop"
                        }
                        GenerationProgressManager.shouldSkip -> {
                            skip(context, jobId)
                            GenerationProgressManager.shouldSkip = false
                        }
                    }
                    delay(1500)
                    state = getJob(context, jobId)
                    connectionFailures = 0
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    connectionFailures++
                    Log.w(TAG, "Agent monitor reconnect $connectionFailures", error)
                    GenerationProgressManager.updateState(
                        true, state.progress, GenerationProgressManager.state.value.currentImage,
                        "PCへ再接続中...（PC側の生成は継続）"
                    )
                    if (connectionFailures >= 5) throw error
                    delay(2000)
                }
            }
            if (clearReconnectState) {
                settings(context).edit().remove(ACTIVE_JOB_KEY).apply()
            }
            return state
        } finally {
            GenerationProgressManager.endGeneration(force = true)
        }
    }

    /**
     * Generates a card thumbnail on the PC and returns only its remote URI. No image bytes
     * are written to Android storage; Glide displays this URI with disk caching disabled.
     */
    suspend fun generateThumbnail(
        context: Context,
        request: AgentGenerationRequest
    ): android.net.Uri {
        if (!isAvailable(context)) {
            throw IOException("PC生成エージェントに接続できません")
        }
        val accepted = submit(
            context,
            listOf(request.copy(purpose = "thumbnail")),
            persistForReconnect = false
        )
        val completed = monitor(
            context,
            initial = accepted,
            silent = true,
            clearReconnectState = false
        )
        val url = completed.imageUrls.firstOrNull()
            ?: throw IOException(completed.error ?: "PCにサムネイルが保存されませんでした")
        return android.net.Uri.parse(url)
    }

    suspend fun resumePendingJob(context: Context): AgentJobState? {
        val id = settings(context).getString(ACTIVE_JOB_KEY, null) ?: return null
        return try {
            val state = getJob(context, id)
            if (state.isTerminal) {
                settings(context).edit().remove(ACTIVE_JOB_KEY).apply()
                GenerationProgressManager.endGeneration(force = true)
                null
            } else {
                monitor(context, state)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Could not resume agent job yet", error)
            GenerationProgressManager.endGeneration(force = true)
            null
        }
    }

    private fun parseJob(context: Context, json: JSONObject): AgentJobState {
        val images = json.optJSONArray("images") ?: JSONArray()
        val imageUrls = buildList {
            for (index in 0 until images.length()) {
                val path = images.optJSONObject(index)?.optString("url").orEmpty()
                if (path.isNotBlank()) add(absoluteUrl(context, path))
            }
        }
        return AgentJobState(
            id = json.getString("id"),
            status = json.getString("status"),
            total = json.optInt("total", 1),
            completed = json.optInt("completed"),
            failed = json.optInt("failed"),
            progress = json.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f),
            previewUrl = json.optString("preview_url").takeIf { it.isNotBlank() },
            imageUrls = imageUrls,
            error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    private fun requestJson(
        context: Context,
        path: String,
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject {
        val configured = baseUrl(context)
        if (configured.isBlank()) throw IOException("PC生成エージェントのURLが未設定です")
        val target = if (path.startsWith("http")) path else "$configured${if (path.startsWith('/')) path else "/$path"}"
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            val key = apiKey(context)
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (body != null) OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            val status = connection.responseCode
            val source = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = source?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("error") }.getOrDefault(text)
                throw IOException("PC生成エージェント HTTP $status: $detail")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadPreview(context: Context, path: String) = withContext(Dispatchers.IO) {
        try {
            val connection = URL(absoluteUrl(context, path)).openConnection().apply {
                connectTimeout = 3000
                readTimeout = 5000
                useCaches = false
            }
            connection.getInputStream().use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
