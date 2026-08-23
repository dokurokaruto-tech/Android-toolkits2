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
    val purpose: String = "image",
    val tags: List<String> = emptyList(),
    val cardStates: Map<String, Int> = emptyMap(),
    val randomPickedIds: Set<String> = emptySet(),
    val randomEnabledCategories: Set<String> = emptySet()
)

data class AgentGeneratedFolder(val date: String, val count: Int, val thumbnailUrl: String?)
data class AgentGeneratedImage(
    val name: String,
    val url: String,
    val thumbnailUrl: String,
    val createdAt: String,
    val tags: List<String> = emptyList(),
    val cardStates: Map<String, Int> = emptyMap(),
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val sampler: String? = null,
    val prompt: String? = null,
    val randomPickedIds: Set<String> = emptySet(),
    val randomEnabledCategories: Set<String> = emptySet()
)

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
    private const val ACTIVE_JOB_TAGS_KEY = "generation_agent_active_job_tags"
    private const val ACTIVE_JOB_CARDS_KEY = "generation_agent_active_job_cards"
    private const val ACTIVE_JOB_RANDOM_KEY = "generation_agent_active_job_random"
    private const val ACTIVE_JOB_KIND_KEY = "generation_agent_active_job_kind"
    private const val ACTIVE_JOB_THUMB_TARGETS_KEY = "generation_agent_active_job_thumb_targets"
    private const val LAST_GOOD_URL_KEY = "remote_server_url_last_good"
    private const val ALT_URL_KEY = "remote_server_url_alts"
    private const val TAG = "GenerationAgent"

    private fun settings(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun normalizeBase(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (url.isNotEmpty() && !url.startsWith("http")) url = "http://$url"
        return url
    }

    private fun primaryUrl(context: Context): String =
        normalizeBase(settings(context).getString("remote_server_url", "") ?: "")

    fun candidateBases(context: Context): List<String> {
        val prefs = settings(context)
        val ordered = mutableListOf<String>()
        ordered.add(normalizeBase(prefs.getString(LAST_GOOD_URL_KEY, "") ?: ""))
        ordered.add(primaryUrl(context))
        (prefs.getString(ALT_URL_KEY, "") ?: "")
            .split(',', '\n', ' ', ';')
            .map { normalizeBase(it) }
            .forEach { ordered.add(it) }
        return ordered.filter { it.isNotBlank() }.distinct()
    }

    private fun rememberGoodBase(context: Context, base: String) {
        if (base.isBlank()) return
        settings(context).edit().putString(LAST_GOOD_URL_KEY, base).apply()
    }

    private fun baseUrl(context: Context): String =
        candidateBases(context).firstOrNull() ?: ""

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

    fun isThumbnailJob(context: Context): Boolean =
        settings(context).getString(ACTIVE_JOB_KIND_KEY, ThumbnailBindPolicy.JOB_KIND_IMAGE) ==
            ThumbnailBindPolicy.JOB_KIND_THUMBNAIL

    fun pendingThumbnailTargets(context: Context): List<ThumbnailBindPolicy.Target> =
        ThumbnailBindPolicy.decodeTargets(settings(context).getString(ACTIVE_JOB_THUMB_TARGETS_KEY, null))

    suspend fun isAvailable(context: Context): Boolean {
        val diagnosis = probe(context)
        return diagnosis.code == AgentConnectionClassifier.OK ||
            diagnosis.code == AgentConnectionClassifier.SD_DOWN
    }

    /** 接続先へ /health を叩き、成功でも失敗でも原因を残す。予備URLがあれば順に試す。 */
    suspend fun probe(context: Context, action: String = "接続テスト"): AgentConnectionDiagnosis = withContext(Dispatchers.IO) {
        val bases = candidateBases(context)
        if (bases.isEmpty()) {
            val diagnosis = AgentConnectionClassifier.fromException(
                IOException("PC生成エージェントのURLが未設定です"),
                "(未設定)",
                "/api/v1/health"
            )
            AgentConnectionLog.record(context, action, diagnosis)
            return@withContext diagnosis
        }
        val reports = mutableListOf<String>()
        var lastFail: AgentConnectionDiagnosis? = null
        for (base in bases) {
            try {
                val json = requestOnce(context, base, "/api/v1/health", "GET", null)
                rememberGoodBase(context, base)
                val diagnosis = AgentConnectionClassifier.fromHealth(
                    service = json.optString("service"),
                    sdReachable = if (json.has("sd_reachable")) json.optBoolean("sd_reachable") else null,
                    target = AgentConnectionClassifier.redactUrl(base)
                ).let { result ->
                    if (base != primaryUrl(context) && primaryUrl(context).isNotBlank()) {
                        result.copy(
                            nextStep = "通ったのは $base 。主URL ${primaryUrl(context)} は届いていない。PC画面の Alternative network URL を主URLにするか、予備URLに 100.x を入れておく。"
                        )
                    } else result
                }
                AgentConnectionLog.record(context, action, diagnosis)
                return@withContext diagnosis
            } catch (error: Exception) {
                val diagnosis = AgentConnectionClassifier.fromException(
                    error,
                    AgentConnectionClassifier.redactUrl(base),
                    "/api/v1/health"
                )
                reports.add("${AgentConnectionClassifier.redactUrl(base)} → ${diagnosis.code}")
                lastFail = diagnosis
                AgentConnectionLog.record(context, "$action ${diagnosis.code}", diagnosis)
            }
        }
        val failed = lastFail ?: AgentConnectionClassifier.fromException(
            IOException("接続先が空です"),
            "(未設定)",
            "/api/v1/health"
        )
        val combined = failed.copy(
            reason = failed.reason + " 試した接続先: " + reports.joinToString(" / "),
            nextStep = failed.nextStep
        )
        AgentConnectionLog.record(context, action, combined)
        combined
    }

    suspend fun submit(
        context: Context,
        requests: List<AgentGenerationRequest>,
        persistForReconnect: Boolean = true,
        jobKind: String = ThumbnailBindPolicy.JOB_KIND_IMAGE,
        thumbnailTargets: List<ThumbnailBindPolicy.Target> = emptyList()
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
                val tags = JSONArray()
                request.tags.forEach { tag ->
                    if (tag.isNotBlank()) tags.put(tag)
                }
                put("tags", tags)
                put("card_states", JSONObject().also { states ->
                    request.cardStates.forEach { (id, level) ->
                        if (id.isNotBlank() && level in 1..3) states.put(id, level)
                    }
                })
                put("random_picked_ids", GeneratedImageTagBinding.encodeStringSet(request.randomPickedIds))
                put("random_categories", GeneratedImageTagBinding.encodeStringSet(request.randomEnabledCategories))
            })
        }
        val body = JSONObject().apply {
            put("client_request_id", UUID.randomUUID().toString())
            put("tasks", tasks)
        }
        val state = parseJob(context, requestJson(context, "/api/v1/jobs", "POST", body))
        if (persistForReconnect) {
            // Synchronous commit closes the tiny crash window between PC acceptance and local reconnect state.
            // Tags are stored with the job so a later reconnect can still bind them after the app died.
            val editor = settings(context).edit()
                .putString(ACTIVE_JOB_KEY, state.id)
                .putString(ACTIVE_JOB_KIND_KEY, jobKind)
                .putString(ACTIVE_JOB_TAGS_KEY, GeneratedImageTagBinding.encodeTagLists(requests.map { it.tags }))
                .putString(ACTIVE_JOB_CARDS_KEY, GeneratedImageTagBinding.encodeCardStateLists(requests.map { it.cardStates }))
                .putString(
                    ACTIVE_JOB_RANDOM_KEY,
                    GeneratedImageTagBinding.encodeRandomMetaLists(
                        requests.map { it.randomPickedIds },
                        requests.map { it.randomEnabledCategories }
                    )
                )
            if (jobKind == ThumbnailBindPolicy.JOB_KIND_THUMBNAIL) {
                editor.putString(ACTIVE_JOB_THUMB_TARGETS_KEY, ThumbnailBindPolicy.encodeTargets(thumbnailTargets))
            } else {
                editor.remove(ACTIVE_JOB_THUMB_TARGETS_KEY)
            }
            editor.commit()
        }
        seedCompletedUrls(context, state.imageUrls)
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

    suspend fun deleteLibraryImage(context: Context, uri: android.net.Uri): Boolean {
        val ref = GeneratedImageIdentity.remoteRef(uri.toString()) ?: return false
        return deleteLibraryImage(context, ref.date, ref.name)
    }

    suspend fun deleteLibraryImage(context: Context, date: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        try {
            requestJson(context, "/api/v1/library/images?date=$encodedDate&name=$encodedName", "DELETE")
            true
        } catch (error: IOException) {
            if (error.message?.contains("HTTP 404") == true) true else throw error
        }
    }

    suspend fun fetchImages(context: Context, date: String): List<AgentGeneratedImage> = withContext(Dispatchers.IO) {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val array = requestJson(context, "/api/v1/library/images?date=$encodedDate").optJSONArray("images") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val tagsArray = item.optJSONArray("tags") ?: JSONArray()
                val tags = buildList {
                    for (tagIndex in 0 until tagsArray.length()) {
                        val tag = tagsArray.optString(tagIndex).trim()
                        if (tag.isNotEmpty()) add(tag)
                    }
                }
                val parameters = item.optJSONObject("parameters")
                add(
                    AgentGeneratedImage(
                        name = item.getString("name"),
                        url = absoluteUrl(context, item.getString("url")),
                        thumbnailUrl = absoluteUrl(
                            context,
                            item.optString("thumbnail_url", item.getString("url"))
                        ),
                        createdAt = item.optString("created_at"),
                        tags = tags,
                        cardStates = GeneratedImageTagBinding.parseCardStates(item.optJSONObject("card_states")),
                        width = parameters?.optInt("width", 0)?.takeIf { it > 0 },
                        height = parameters?.optInt("height", 0)?.takeIf { it > 0 },
                        steps = parameters?.optInt("steps", 0)?.takeIf { it > 0 },
                        sampler = parameters?.optString("sampler_name")?.takeIf { it.isNotBlank() },
                        prompt = parameters?.optString("prompt")?.takeIf { it.isNotBlank() },
                        randomPickedIds = GeneratedImageTagBinding.parseStringSet(item.optJSONArray("random_picked_ids")),
                        randomEnabledCategories = GeneratedImageTagBinding.parseStringSet(item.optJSONArray("random_categories"))
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
        seedCompletedUrls(context, state.imageUrls)
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
                    seedCompletedUrls(context, state.imageUrls)
                    connectionFailures = 0
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    connectionFailures++
                    val diagnosis = AgentConnectionClassifier.fromException(
                        error,
                        AgentConnectionClassifier.redactUrl(baseUrl(context)),
                        "/api/v1/jobs/$jobId"
                    )
                    AgentConnectionLog.record(context, "監視再接続 $connectionFailures", diagnosis)
                    Log.w(TAG, "Agent monitor reconnect $connectionFailures ${diagnosis.code}", error)
                    GenerationProgressManager.updateState(
                        true, state.progress, GenerationProgressManager.state.value.currentImage,
                        "再接続中 ${connectionFailures}/5 [${diagnosis.code}] ${diagnosis.title}"
                    )
                    if (connectionFailures >= 5) throw IOException(diagnosis.displayText(), error)
                    delay(2000)
                }
            }
            seedCompletedUrls(context, state.imageUrls)
            if (clearReconnectState) {
                clearReconnectState(context)
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
        val diagnosis = probe(context)
        if (diagnosis.code != AgentConnectionClassifier.OK &&
            diagnosis.code != AgentConnectionClassifier.SD_DOWN
        ) {
            throw IOException(diagnosis.displayText())
        }
        val accepted = submit(
            context,
            listOf(request.copy(purpose = "thumbnail")),
            persistForReconnect = true,
            jobKind = ThumbnailBindPolicy.JOB_KIND_THUMBNAIL
        )
        val completed = monitor(
            context,
            initial = accepted,
            silent = false
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
                seedCompletedUrls(context, state.imageUrls)
                clearReconnectState(context)
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

    private fun seedCompletedUrls(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        if (isThumbnailJob(context)) {
            ThumbnailBinder.applyCompleted(context, urls, pendingThumbnailTargets(context))
            return
        }
        val tagLists = GeneratedImageTagBinding.decodeTagLists(settings(context).getString(ACTIVE_JOB_TAGS_KEY, null))
        val cardLists = GeneratedImageTagBinding.decodeCardStateLists(settings(context).getString(ACTIVE_JOB_CARDS_KEY, null))
        val randomLists = GeneratedImageTagBinding.decodeRandomMetaLists(settings(context).getString(ACTIVE_JOB_RANDOM_KEY, null))
        val prepared = tagLists.mapIndexed { index, tags ->
            val random = randomLists.getOrNull(index)
            GeneratedImageTagBinding.PreparedImage(
                prompt = "",
                negativePrompt = "",
                tags = tags,
                cardStates = cardLists.getOrNull(index).orEmpty(),
                randomPickedIds = random?.first.orEmpty(),
                randomEnabledCategories = random?.second.orEmpty()
            )
        }
        if (prepared.isEmpty()) return
        val tagsByUrl = GeneratedImageTagBinding.tagsForCompletedUrls(urls, prepared).toMap()
        val cardsByUrl = GeneratedImageTagBinding.cardStatesForCompletedUrls(urls, prepared).toMap()
        urls.forEachIndexed { order, url ->
            val item = GeneratedImageTagBinding.taskIndexFromUrl(url)
                ?.let { prepared.getOrNull(it - 1) }
                ?: prepared.getOrNull(order)
            GeneratedImageDraftStore.seedGeneratedSource(
                context,
                android.net.Uri.parse(url),
                tagsByUrl[url].orEmpty(),
                cardsByUrl[url].orEmpty(),
                prompt = item?.prompt,
                randomPickedIds = item?.randomPickedIds.orEmpty(),
                randomEnabledCategories = item?.randomEnabledCategories.orEmpty()
            )
        }
    }

    private fun clearReconnectState(context: Context) {
        settings(context).edit()
            .remove(ACTIVE_JOB_KEY)
            .remove(ACTIVE_JOB_TAGS_KEY)
            .remove(ACTIVE_JOB_CARDS_KEY)
            .remove(ACTIVE_JOB_RANDOM_KEY)
            .remove(ACTIVE_JOB_KIND_KEY)
            .remove(ACTIVE_JOB_THUMB_TARGETS_KEY)
            .apply()
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
        body: JSONObject? = null,
        recordFailure: Boolean = true
    ): JSONObject {
        val bases = candidateBases(context)
        if (bases.isEmpty()) {
            val error = IOException("PC生成エージェントのURLが未設定です")
            if (recordFailure) {
                AgentConnectionLog.record(
                    context,
                    "$method $path",
                    AgentConnectionClassifier.fromException(error, "(未設定)", path)
                )
            }
            throw error
        }
        var lastError: Exception? = null
        for (base in bases) {
            try {
                val json = requestOnce(context, base, path, method, body)
                rememberGoodBase(context, base)
                return json
            } catch (error: Exception) {
                lastError = error
                if (error is CancellationException) throw error
                val diagnosis = AgentConnectionClassifier.fromException(
                    error,
                    AgentConnectionClassifier.redactUrl(base),
                    path
                )
                if (recordFailure) {
                    AgentConnectionLog.record(context, "$method $path", diagnosis)
                }
                if (!AgentConnectionClassifier.shouldTryNextEndpoint(diagnosis.code)) throw error
            }
        }
        throw lastError ?: IOException("PC生成エージェントとの通信に失敗しました")
    }

    private fun requestOnce(
        context: Context,
        base: String,
        path: String,
        method: String,
        body: JSONObject?
    ): JSONObject {
        val target = if (path.startsWith("http")) path else "$base${if (path.startsWith('/')) path else "/$path"}"
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
