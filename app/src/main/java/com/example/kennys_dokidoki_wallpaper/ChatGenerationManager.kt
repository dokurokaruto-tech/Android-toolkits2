package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 画面が閉じられても、バックグラウンド（グローバルコルーチンスコープ）でAIからの返信を
 * 途切れずに受信してセッションを保存し続けるためのマネージャーよ☆
 */
object ChatGenerationManager {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    var isGenerating = false
        private set
    var activeSessionId: String? = null
        private set
    var activeAiNodeId: String? = null
        private set

    // 受信を監視するためのリスナー
    interface Listener {
        fun onProgress(text: String, isComplete: Boolean, modelName: String? = null, error: String? = null)
    }
    private val listeners = mutableListOf<Listener>()

    fun registerListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun cancelActiveGeneration(context: Context) {
        activeJob?.cancel()
        activeJob = null
        isGenerating = false
        activeSessionId = null
        activeAiNodeId = null
        LlmForegroundService.stop(context)
        notifyError("返信の生成がキャンセルされました。")
    }

    // 生成を開始する
    fun startGeneration(
        context: Context,
        engine: String, // "CLOUD" or "LOCAL"
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        userNode: ChatNode,
        aiNode: ChatNode
    ) {
        // すでに動いていたら一度安全にキャンセル
        cancelActiveGeneration(context)

        isGenerating = true
        activeSessionId = sessionId
        activeAiNodeId = aiNode.id

        // サービスを開始して、OSによるプロセスkillを防ぐのよ！
        LlmForegroundService.start(context)

        activeJob = scope.launch {
            if (engine == "LOCAL") {
                runLocalResponse(context, sessionId, systemPrompt, chatTree, userNode, aiNode)
            } else {
                runCloudResponse(context, sessionId, systemPrompt, chatTree, userNode, aiNode)
            }
        }
    }

    private suspend fun runLocalResponse(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        userNode: ChatNode,
        aiNode: ChatNode
    ) {
        val models = LocalModelManager.getAllModels(context)
        if (models.isEmpty()) {
            aiNode.text = "【エラー】モデルがダウンロードされていません。設定からダウンロードを実行してください。"
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = "No local models")
            return
        }

        // 思考中アニメーション
        var dots = 0
        val thinkingJob = scope.launch {
            while (isActive) {
                delay(400)
                dots = (dots + 1) % 4
                aiNode.text = "🧠 推論中 (Local)" + ".".repeat(dots)
                notifyProgress(aiNode.text, false)
            }
        }

        try {
            // モデルが未ロードなら自動ロード
            if (!LlmInferenceEngine.isModelLoaded()) {
                aiNode.text = "📥 モデルをロードしています..."
                notifyProgress(aiNode.text, false)
                LlmForegroundService.updateNotification(context, "モデルをロード中... 🔄")

                val errorMsg = withContext(Dispatchers.IO) {
                    LlmInferenceEngine.autoLoadModelDetailed(context)
                }
                if (errorMsg != null) {
                    thinkingJob.cancel()
                    aiNode.text = "【エラー】モデルのロードに失敗しました。\n$errorMsg"
                    saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = errorMsg)
                    return
                }
            }

            LlmForegroundService.start(context)
            LlmForegroundService.updateNotification(context, "${LlmInferenceEngine.loadedModelName} で推論中... 🧠✨")

            val history = getRecentHistory(chatTree, aiNode.parentId)
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isSuggestEnabled = prefs.getBoolean("chat_suggest_reply", true)
            val mappedHistory = if (isSuggestEnabled && history.isNotEmpty() && history.last().isUser) {
                history.mapIndexed { index, node ->
                    if (index == history.lastIndex) {
                        node.copy(
                            text = ChatInstructionPolicy.applySuggestToUserText(
                                node.text,
                                true,
                                prefs.getString(ChatInstructionPolicy.SUGGEST_KEY, null),
                                prefs.getString("chat_suggest_custom_instructions", "") ?: ""
                            )
                        )
                    } else {
                        node
                    }
                }
            } else {
                history
            }
            val prompt = LlmInferenceEngine.buildChatPrompt(systemPrompt, mappedHistory)

            thinkingJob.cancel()
            aiNode.text = ""
            notifyProgress("", false)
            var tokenCount = 0

            withContext(Dispatchers.IO) {
                LlmInferenceEngine.generate(
                    prompt = prompt,
                    onToken = { token ->
                        scope.launch(Dispatchers.Main) {
                            aiNode.text += token
                            tokenCount++
                            notifyProgress(aiNode.text, false)

                            if (tokenCount % 10 == 0) {
                                LlmForegroundService.updateNotification(
                                    context,
                                    "生成中... ${tokenCount}トークン 🧠✨"
                                )
                            }
                        }
                    },
                    onComplete = {
                        scope.launch(Dispatchers.Main) {
                            aiNode.modelName = LlmInferenceEngine.loadedModelName
                            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true)
                            LlmForegroundService.updateNotification(context, "推論完了 ✅ (${tokenCount}トークン)")
                            
                            Handler(Looper.getMainLooper()).postDelayed({
                                LlmForegroundService.stop(context)
                            }, 2000)
                        }
                    },
                    onError = { errorMsg ->
                        scope.launch(Dispatchers.Main) {
                            if (aiNode.text.isEmpty()) {
                                aiNode.text = "【エラー】推論プロセスで不具合が発生しました: $errorMsg"
                            }
                            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = errorMsg)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            thinkingJob.cancel()
            aiNode.text = "【エラー】推論実行中に例外が発生しました: ${e.message}"
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = e.message)
        }
    }

    private suspend fun runCloudResponse(
        context: Context,
        sessionId: String,
        systemPrompt: String,
        chatTree: ChatTree,
        userNode: ChatNode,
        aiNode: ChatNode
    ) {
        var dots = 0
        val thinkingJob = scope.launch {
            while (isActive) {
                delay(400)
                dots = (dots + 1) % 4
                aiNode.text = "推論中" + ".".repeat(dots)
                notifyProgress(aiNode.text, false)
            }
        }

        try {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
            
            var apiKey = ""
            var apiUrl = ""
            var modelName = ""
            
            if (provider == "OPENROUTER") {
                apiKey = OpenRouterManager.getActiveApiKey(context) ?: ""
                apiUrl = "https://openrouter.ai/api/v1/chat/completions"
                modelName = prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free") ?: "deepseek/deepseek-v4-flash:free"
            } else {
                apiKey = prefs.getString("xai_api_key", "")?.trim() ?: ""
                if (apiKey.isNotEmpty() && !apiKey.startsWith("xai-")) apiKey = "xai-$apiKey"
                apiUrl = "https://api.x.ai/v1/chat/completions"
                modelName = "grok-2-1212"
            }

            if (apiKey.isEmpty()) {
                thinkingJob.cancel()
                aiNode.text = "【エラー】${provider}のAPIキーが未設定です。"
                saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = "API key missing")
                return
            }

            LlmForegroundService.updateNotification(context, "$modelName で返信を生成中... 🧠✨")

            val history = getRecentHistory(chatTree, aiNode.parentId)
            val isSuggestEnabled = prefs.getBoolean("chat_suggest_reply", true)
            
            val jsonArray = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            for (i in history.indices) {
                val msg = history[i]
                val contentText = ChatInstructionPolicy.applySuggestToUserText(
                    msg.text,
                    isSuggestEnabled && i == history.lastIndex && msg.isUser,
                    prefs.getString(ChatInstructionPolicy.SUGGEST_KEY, null),
                    prefs.getString("chat_suggest_custom_instructions", "") ?: ""
                )
                jsonArray.put(JSONObject().put("role", if (msg.isUser) "user" else "assistant").put("content", contentText))
            }

            val requestBody = JSONObject().apply {
                put("messages", jsonArray)
                put("model", modelName)
                put("stream", true)
            }

            val fullReply = withContext(Dispatchers.IO) {
                var reply = ""
                try {
                    val url = URL(apiUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "POST"
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("Content-Type", "application/json")
                        if (provider == "OPENROUTER") {
                            setRequestProperty("HTTP-Referer", "https://github.com/example/android-toolkits")
                            setRequestProperty("X-Title", "Android Toolkits")
                        }
                        doOutput = true
                    }

                    OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

                    if (conn.responseCode == 200) {
                        withContext(Dispatchers.Main) {
                            thinkingJob.cancel()
                            aiNode.text = ""
                            notifyProgress("", false)
                        }

                        val reader = conn.inputStream.bufferedReader()
                        reader.useLines { lines ->
                            lines.forEach { line ->
                                if (!isActive) return@useLines
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6).trim()
                                    if (data == "[DONE]") return@forEach
                                    
                                    try {
                                        val json = JSONObject(data)
                                        val delta = json.getJSONArray("choices")
                                            .getJSONObject(0)
                                            .getJSONObject("delta")
                                        
                                        if (delta.has("content")) {
                                            val content = delta.getString("content")
                                            reply += content
                                            
                                            withContext(Dispatchers.Main) {
                                                aiNode.text = reply
                                                notifyProgress(reply, false)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // パースエラー無視
                                    }
                                }
                            }
                        }
                        if (provider == "OPENROUTER" && reply.isNotEmpty()) {
                            OpenRouterManager.incrementUsage(context, apiKey)
                        }
                    } else {
                        val errorMsg = conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                        withContext(Dispatchers.Main) {
                            thinkingJob.cancel()
                            val friendlyMsg = when(conn.responseCode) {
                                429 -> "【エラー】リクエストが制限されました (HTTP 429)。\n対象のモデルは現在、一時的に利用制限がかかっています。"
                                401 -> "【エラー】認証に失敗しました (HTTP 401)。"
                                404 -> "【エラー】指定されたモデルが見つかりません (HTTP 404)。"
                                else -> "【エラー】通信エラーが発生しました (Code: ${conn.responseCode}): $errorMsg"
                            }
                            aiNode.text = friendlyMsg
                            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = errorMsg)
                        }
                        return@withContext null
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        thinkingJob.cancel()
                        aiNode.text = "【エラー】通信エラー: ${e.message}"
                        saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = e.message)
                    }
                    return@withContext null
                }
                reply
            }

            if (fullReply != null) {
                aiNode.modelName = modelName
                if (fullReply.isEmpty()) {
                    aiNode.text = "【エラー】AIからの応答が空でした。別のモデルを試すか、もう一度実行してみてね。"
                }
                saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true)
            }

        } catch (e: Exception) {
            thinkingJob.cancel()
            aiNode.text = "【エラー】システムエラーが発生しました: ${e.message}"
            saveAndNotify(context, sessionId, chatTree, aiNode, isComplete = true, error = e.message)
        }
    }

    private fun getRecentHistory(chatTree: ChatTree, startNodeId: String?): List<ChatNode> {
        val history = mutableListOf<ChatNode>()
        var trace = startNodeId
        while (trace != null) {
            val node = chatTree.nodes[trace]
            if (node != null) {
                history.add(0, node)
                trace = node.parentId
            } else break
        }
        return history.takeLast(10)
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

        // ディスクに即時保存
        ChatSessionManager.saveSessionData(context, sessionId, chatTree)
        
        // メインスレッドでリスナー通知＆クリーンアップ
        scope.launch(Dispatchers.Main) {
            listeners.forEach { 
                it.onProgress(aiNode.text, isComplete, modelName = aiNode.modelName, error = error)
            }

            if (isComplete) {
                isGenerating = false
                activeSessionId = null
                activeAiNodeId = null
                LlmForegroundService.stop(context)
            }
        }
    }

    private fun notifyProgress(text: String, isComplete: Boolean) {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { 
                it.onProgress(text, isComplete)
            }
        }
    }

    private fun notifyError(errorMsg: String) {
        scope.launch(Dispatchers.Main) {
            listeners.forEach { 
                it.onProgress("", false, error = errorMsg)
            }
        }
    }
}
