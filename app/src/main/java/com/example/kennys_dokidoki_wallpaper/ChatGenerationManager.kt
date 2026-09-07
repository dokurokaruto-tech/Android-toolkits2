package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 画面が閉じられても AI の返信をバックグラウンドで受信し続け、セッションへ保存するマネージャ。
 *
 *   startGeneration ──▶ LlmForegroundService.start
 *                   ├─ CLOUD: SSE ストリーム ─┐
 *                   └─ LOCAL: llama/MediaPipe ─┴─▶ StreamBuffer (40ms 毎にまとめて UI へ)
 *                                                     └─▶ saveAndNotify ──▶ ChatSessionManager
 *
 * 旧実装からの修正:
 *   - `reply += content` (O(n²)) → StringBuilder
 *   - トークンごとに Main へ切替え + 全リスナー通知 → 40ms 間隔でまとめる (最大 25 回/秒)
 *   - HttpURLConnection にタイムアウト無し → connect 15s / read 60s
 *   - 例外時に disconnect() されない → finally で必ず切断。キャンセル時も即切断
 *   - 例外メッセージ (スタック情報) をチャット本文として永続化 → 利用者向け文言に置換、詳細は Log と error 引数へ
 *   - API キーを平文 Prefs から取得 → AppSecrets / OpenRouterManager (暗号化)
 *   - xAI キーに "xai-" を勝手に前置 → 廃止 (入力をそのまま使う)
 *
 * 公開 API は旧版と同一。
 */
object ChatGenerationManager {
    private const val TAG = "ChatGeneration"

    const val ENGINE_CLOUD = "CLOUD"
    const val ENGINE_LOCAL = "LOCAL"
    const val PROVIDER_GROK = "GROK"
    const val PROVIDER_OPENROUTER = "OPENROUTER"

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    private const val XAI_URL = "https://api.x.ai/v1/chat/completions"
    private const val DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-v4-flash:free"
    private const val DEFAULT_XAI_MODEL = "grok-3-mini"
    private const val PREF_XAI_MODEL = "chat_xai_model"
    private const val APP_REFERER = "https://github.com/dokurokaruto-tech/Android-toolkits2"
    private const val APP_TITLE = "Kennys Dokidoki Wallpaper"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val HISTORY_LIMIT = 10
    private const val UI_FLUSH_INTERVAL_MS = 40L
    private const val THINKING_TICK_MS = 400L
    private const val THINKING_DOTS = 4
    private const val NOTIFICATION_TOKEN_STEP = 25
    private const val SERVICE_STOP_DELAY_MS = 2_000L
    private const val ERROR_DETAIL_LIMIT = 300
    private const val SSE_DATA_PREFIX = "data: "
    private const val SSE_DONE = "[DONE]"

    // ChatAdapter が「生成中」を判定する接頭辞。旧アダプタ互換のため値は据え置き。
    const val STATUS_THINKING_CLOUD = "推論中"
    const val STATUS_THINKING_LOCAL = "🧠 推論中 (Local)"
    const val STATUS_LOADING_MODEL = "📥 モデルをロードしています..."
    private val STATUS_PREFIXES = listOf("思考中", "推論中", "🧠", "📥")

    private const val MSG_NO_LOCAL_MODEL = "【エラー】ローカルモデルが未ダウンロードです。設定 > ローカル LLM から取得してください。"
    private const val MSG_MODEL_LOAD_FAILED = "【エラー】モデルの読み込みに失敗しました。"
    private const val MSG_NETWORK = "【エラー】通信に失敗しました。ネットワーク接続を確認して再試行してください。"
    private const val MSG_EMPTY_REPLY = "【エラー】AI から空の応答が返りました。別のモデルを試すか、再実行してください。"
    private const val MSG_INFERENCE_FAILED = "【エラー】推論中に問題が発生しました。再実行してください。"
    private const val MSG_CANCELED = "返信の生成をキャンセルしました。"

    fun isStatusText(text: String): Boolean = STATUS_PREFIXES.any { text.startsWith(it) }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var activeJob: Job? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    var isGenerating = false
        private set
    var activeSessionId: String? = null
        private set
    var activeAiNodeId: String? = null
        private set

    interface Listener {
        fun onProgress(text: String, isComplete: Boolean, modelName: String? = null, error: String? = null)
    }

    fun registerListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun cancelActiveGeneration(context: Context) {
        activeJob?.cancel()
        activeJob = null
        activeConnection?.let { conn -> Thread { runCatching { conn.disconnect() } }.start() }
        activeConnection = null
        clearActive()
        LlmForegroundService.stop(context)
        notifyError(MSG_CANCELED)
    }

    fun startGeneration(
        context: Context,
        engine: String,
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        userNode: ChatNode,
        aiNode: ChatNode
    ) {
        cancelActiveGeneration(context)

        isGenerating = true
        activeSessionId = sessionId
        activeAiNodeId = aiNode.id
        LlmForegroundService.start(context)

        val appContext = context.applicationContext
        activeJob = scope.launch {
            if (engine == ENGINE_LOCAL) {
                runLocalResponse(appContext, sessionId, systemPrompt, chatTree, aiNode)
            } else {
                runCloudResponse(appContext, sessionId, systemPrompt, chatTree, aiNode)
            }
        }
    }

    // ------------------------------------------------------------------ local

    private suspend fun runLocalResponse(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        aiNode: ChatNode
    ) {
        if (LocalModelManager.getAllModels(context).isEmpty()) {
            aiNode.text = MSG_NO_LOCAL_MODEL
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = "No local models")
            return
        }

        var ticker = startThinkingTicker(aiNode, STATUS_THINKING_LOCAL)
        try {
            if (!LlmInferenceEngine.isModelLoaded()) {
                ticker.cancel()
                aiNode.text = STATUS_LOADING_MODEL
                notifyProgress(aiNode.text)
                LlmForegroundService.updateNotification(context, "モデルを読み込み中…")

                val loadError = withContext(Dispatchers.IO) { LlmInferenceEngine.autoLoadModelDetailed(context) }
                if (loadError != null) {
                    aiNode.text = MSG_MODEL_LOAD_FAILED
                    saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = loadError)
                    return
                }
                ticker = startThinkingTicker(aiNode, STATUS_THINKING_LOCAL)
            }

            LlmForegroundService.updateNotification(context, "${LlmInferenceEngine.loadedModelName} で推論中…")

            val prefs = settings(context)
            val history = applySuggestInstruction(prefs, getRecentHistory(chatTree, aiNode.parentId))
            val prompt = LlmInferenceEngine.buildChatPrompt(systemPrompt, history)

            ticker.cancel()
            aiNode.text = ""
            notifyProgress("")

            val buffer = StreamBuffer(aiNode)
            var tokenCount = 0

            withContext(Dispatchers.IO) {
                LlmInferenceEngine.generate(
                    prompt = prompt,
                    onToken = { token ->
                        buffer.append(token)
                        tokenCount++
                        if (tokenCount % NOTIFICATION_TOKEN_STEP == 0) {
                            LlmForegroundService.updateNotification(context, "生成中… ${tokenCount} トークン")
                        }
                    },
                    onComplete = {
                        mainHandler.post {
                            buffer.finish()
                            aiNode.modelName = LlmInferenceEngine.loadedModelName
                            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true)
                            LlmForegroundService.updateNotification(context, "推論完了 (${tokenCount} トークン)")
                            mainHandler.postDelayed({ LlmForegroundService.stop(context) }, SERVICE_STOP_DELAY_MS)
                        }
                    },
                    onError = { errorMsg ->
                        mainHandler.post {
                            val partial = buffer.finish()
                            if (partial.isEmpty()) {
                                aiNode.text = MSG_INFERENCE_FAILED
                            }
                            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = errorMsg)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            ticker.cancel()
            Log.e(TAG, "local inference failed", e)
            aiNode.text = MSG_INFERENCE_FAILED
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = e.toString())
        }
    }

    // ------------------------------------------------------------------ cloud

    private data class CloudEndpoint(
        val url: String,
        val apiKey: String?,
        val model: String,
        val isOpenRouter: Boolean
    )

    private sealed class Outcome {
        object Success : Outcome()
        data class Failure(val userMessage: String, val detail: String) : Outcome()
    }

    private suspend fun runCloudResponse(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        aiNode: ChatNode
    ) {
        val prefs = settings(context)
        val provider = prefs.getString(PrefKeys.CHAT_CLOUD_PROVIDER, PROVIDER_GROK) ?: PROVIDER_GROK
        val endpoint = resolveEndpoint(context, prefs, provider)

        if (endpoint.apiKey.isNullOrBlank()) {
            aiNode.text = "【エラー】$provider の API キーが未設定です。設定 > AI から登録してください。"
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = "API key missing")
            return
        }

        val ticker = startThinkingTicker(aiNode, STATUS_THINKING_CLOUD)
        try {
            LlmForegroundService.updateNotification(context, "${endpoint.model} で返信を生成中…")

            val history = getRecentHistory(chatTree, aiNode.parentId)
            val body = JSONObject()
                .put("model", endpoint.model)
                .put("stream", true)
                .put("messages", buildMessages(prefs, systemPrompt, history))

            val buffer = StreamBuffer(aiNode)
            val job = activeJob
            val outcome = withContext(Dispatchers.IO) {
                streamCompletion(
                    endpoint = endpoint,
                    body = body,
                    buffer = buffer,
                    isCancelled = { job?.isActive == false },
                    onFirstToken = { ticker.cancel() }
                )
            }
            ticker.cancel()

            when (outcome) {
                is Outcome.Success -> {
                    val text = buffer.finish()
                    if (endpoint.isOpenRouter && text.isNotEmpty()) {
                        OpenRouterManager.incrementUsage(context, endpoint.apiKey)
                    }
                    aiNode.modelName = endpoint.model
                    if (text.isEmpty()) {
                        aiNode.text = MSG_EMPTY_REPLY
                    }
                    saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true)
                }
                is Outcome.Failure -> {
                    buffer.finish()
                    aiNode.text = outcome.userMessage
                    saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = outcome.detail)
                }
            }
        } catch (e: Exception) {
            ticker.cancel()
            Log.e(TAG, "cloud generation failed", e)
            aiNode.text = MSG_NETWORK
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = e.toString())
        }
    }

    private fun resolveEndpoint(context: Context, prefs: SharedPreferences, provider: String): CloudEndpoint {
        if (provider == PROVIDER_OPENROUTER) {
            return CloudEndpoint(
                url = OPENROUTER_URL,
                apiKey = OpenRouterManager.getActiveApiKey(context),
                model = prefs.getString(PrefKeys.CHAT_OPENROUTER_MODEL, DEFAULT_OPENROUTER_MODEL) ?: DEFAULT_OPENROUTER_MODEL,
                isOpenRouter = true
            )
        }
        return CloudEndpoint(
            url = XAI_URL,
            apiKey = AppSecrets.xaiApiKey(context),
            model = prefs.getString(PREF_XAI_MODEL, DEFAULT_XAI_MODEL) ?: DEFAULT_XAI_MODEL,
            isOpenRouter = false
        )
    }

    private fun buildMessages(prefs: SharedPreferences, systemPrompt: String, history: List<ChatNode>): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt))
        val mapped = applySuggestInstruction(prefs, history)
        mapped.forEach { node ->
            messages.put(
                JSONObject()
                    .put("role", if (node.isUser) "user" else "assistant")
                    .put("content", node.text)
            )
        }
        return messages
    }

    /** 直近のユーザー発言にだけ「返信候補を出す」指示を付ける */
    private fun applySuggestInstruction(prefs: SharedPreferences, history: List<ChatNode>): List<ChatNode> {
        val enabled = prefs.getBoolean(PrefKeys.CHAT_SUGGEST_REPLY, true)
        if (!enabled || history.isEmpty() || !history.last().isUser) {
            return history
        }
        val last = history.last()
        val rewritten = last.copy(
            text = ChatInstructionPolicy.applySuggestToUserText(
                last.text,
                true,
                prefs.getString(ChatInstructionPolicy.SUGGEST_KEY, null),
                prefs.getString(PrefKeys.CHAT_SUGGEST_CUSTOM, "") ?: ""
            )
        )
        return history.dropLast(1) + rewritten
    }

    /** IO スレッドで SSE を読み、トークンを buffer へ流す。UI には触らない */
    private fun streamCompletion(
        endpoint: CloudEndpoint,
        body: JSONObject,
        buffer: StreamBuffer,
        isCancelled: () -> Boolean,
        onFirstToken: () -> Unit
    ): Outcome {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "text/event-stream")
                if (endpoint.isOpenRouter) {
                    setRequestProperty("HTTP-Referer", APP_REFERER)
                    setRequestProperty("X-Title", APP_TITLE)
                }
            }
            activeConnection = connection

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Log.w(TAG, "cloud HTTP $status: ${detail.take(ERROR_DETAIL_LIMIT)}")
                return Outcome.Failure(friendlyHttpMessage(status), "HTTP $status: ${detail.take(ERROR_DETAIL_LIMIT)}")
            }

            var first = true
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (isCancelled()) {
                        break
                    }
                    if (!line.startsWith(SSE_DATA_PREFIX)) {
                        continue
                    }
                    val data = line.substring(SSE_DATA_PREFIX.length).trim()
                    if (data == SSE_DONE) {
                        break
                    }
                    val content = extractDelta(data) ?: continue
                    if (first) {
                        first = false
                        onFirstToken()
                    }
                    buffer.append(content)
                }
            }
            return Outcome.Success
        } catch (e: Exception) {
            if (isCancelled()) {
                return Outcome.Failure(MSG_CANCELED, "canceled")
            }
            Log.e(TAG, "cloud stream failed", e)
            return Outcome.Failure(MSG_NETWORK, e.toString())
        } finally {
            activeConnection = null
            connection?.disconnect()
        }
    }

    private fun extractDelta(data: String): String? {
        return try {
            val delta = JSONObject(data).getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
            if (delta.has("content") && !delta.isNull("content")) delta.getString("content") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun friendlyHttpMessage(status: Int): String = when (status) {
        HttpURLConnection.HTTP_UNAUTHORIZED -> "【エラー】認証に失敗しました (HTTP 401)。API キーを確認してください。"
        HttpURLConnection.HTTP_PAYMENT_REQUIRED -> "【エラー】クレジット不足です (HTTP 402)。"
        HttpURLConnection.HTTP_NOT_FOUND -> "【エラー】指定したモデルが見つかりません (HTTP 404)。"
        429 -> "【エラー】リクエスト上限に達しました (HTTP 429)。しばらく待つか別のキー/モデルを使ってください。"
        in 500..599 -> "【エラー】サービス側で障害が発生しています (HTTP $status)。時間をおいて再試行してください。"
        else -> "【エラー】通信エラーが発生しました (HTTP $status)。"
    }

    // --------------------------------------------------------------- helpers

    /**
     * IO スレッドから届くトークンを溜め、UI_FLUSH_INTERVAL_MS ごとにまとめて Main へ流す。
     * 旧実装はトークンごとに withContext(Main) + 全リスナー通知していた。
     */
    private class StreamBuffer(private val node: ChatNode) {
        private val builder = StringBuilder()

        @Volatile
        private var scheduled = false

        private val flush = Runnable {
            scheduled = false
            val snapshot = synchronized(builder) { builder.toString() }
            node.text = snapshot
            listeners.forEach { it.onProgress(snapshot, false) }
        }

        fun append(token: String) {
            synchronized(builder) { builder.append(token) }
            if (!scheduled) {
                scheduled = true
                mainHandler.postDelayed(flush, UI_FLUSH_INTERVAL_MS)
            }
        }

        /** 残りを反映して最終テキストを返す。Main スレッドで呼ぶ */
        fun finish(): String {
            mainHandler.removeCallbacks(flush)
            scheduled = false
            val snapshot = synchronized(builder) { builder.toString() }
            if (snapshot.isNotEmpty()) {
                node.text = snapshot
            }
            return snapshot
        }
    }

    private fun startThinkingTicker(node: ChatNode, base: String): Job = scope.launch {
        var dots = 0
        while (isActive) {
            node.text = base + ".".repeat(dots)
            notifyProgress(node.text)
            dots = (dots + 1) % THINKING_DOTS
            delay(THINKING_TICK_MS)
        }
    }

    private fun getRecentHistory(chatTree: ChatTree, startNodeId: String?): List<ChatNode> {
        val history = ArrayList<ChatNode>()
        var cursor = startNodeId
        while (cursor != null) {
            val node = chatTree.nodes[cursor] ?: break
            history.add(node)
            cursor = node.parentId
        }
        history.reverse()
        return history.takeLast(HISTORY_LIMIT)
    }

    private fun saveAndNotify(
        context: Context,
        sessionId: String,
        chatTree: ChatTree,
        aiNode: ChatNode,
        isComplete: Boolean,
        error: String? = null
    ) {
        if (isComplete && error == null) {
            ChatSuggestionParser.applyTo(aiNode)
        }
        // ツリーは Main スレッドでしか編集されないので、保存も Main で行い競合を避ける。
        // TODO: ChatSessionManager.saveSessionData をスナップショット + IO 書込みに分離する
        ChatSessionManager.saveSessionData(context, sessionId, chatTree)

        val text = aiNode.text
        val model = aiNode.modelName
        mainHandler.post {
            listeners.forEach { it.onProgress(text, isComplete, modelName = model, error = error) }
            if (isComplete) {
                clearActive()
                LlmForegroundService.stop(context)
            }
        }
    }

    private fun clearActive() {
        isGenerating = false
        activeSessionId = null
        activeAiNodeId = null
    }

    private fun notifyProgress(text: String) {
        mainHandler.post { listeners.forEach { it.onProgress(text, false) } }
    }

    private fun notifyError(message: String) {
        mainHandler.post { listeners.forEach { it.onProgress("", false, error = message) } }
    }

    private fun settings(context: Context): SharedPreferences {
        return context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
    }
}
