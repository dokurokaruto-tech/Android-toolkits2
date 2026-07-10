package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var pendingYenCost = 0 // 円単位に変更

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
        pendingYenCost = 0
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
        aiNode: ChatNode,
        pendingYenCost: Int = 0
    ) {
        // すでに動いていたら一度安全にキャンセル
        cancelActiveGeneration(context)

        isGenerating = true
        activeSessionId = sessionId
        activeAiNodeId = aiNode.id
        this.pendingYenCost = pendingYenCost

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
                val customInstructions = prefs.getString("chat_suggest_custom_instructions", "") ?: ""
                val customBlock = if (customInstructions.isNotEmpty()) {
                    "\n■ ユーザー指定のサジェスト追加ルール・例文：\n$customInstructions\n"
                } else {
                    ""
                }
                history.mapIndexed { index, node ->
                    if (index == history.lastIndex) {
                        node.copy(
                            text = node.text + "\n\n" +
                                    "⚠️【最重要：システム指令】⚠️\n" +
                                    "ユーザー（ケニーちゃん）の今回のメッセージに対して、あなたのキャラクター設定（ノアなど）を完璧に維持した魅力的な返答を生成してください。\n" +
                                    "そして、必ずその「返信文の末尾」に、ケニーちゃんが次回タップして返信できるように、ケニーちゃんになりきった3つの返信候補（サジェスト）を正確に付記してください。\n" +
                                    "■ サジェスト作成ルール・絶対厳守：\n" +
                                    "- サジェスト機能は常時ONです。今回の返信がどのような短いセリフや特別な内容であっても、必ず最後の最後に <<<SUGGESTIONS>>> タグで囲んで3つの選択肢を出力してください。\n" +
                                    "- サジェストの文字数はそれぞれ15文字以内で、短くタップしやすいものにしてください。$customBlock\n" +
                                    "出力形式（この形式を必ずあなたの返信の最後の最後にそのまま正確に出力してください。余計な説明文やマークダウンは含めないでください）：\n" +
                                    "<<<SUGGESTIONS>>>\n" +
                                    "A: <返信案A>\n" +
                                    "B: <返信案B>\n" +
                                    "C: <返信案C>\n" +
                                    "<<</SUGGESTIONS>>>"
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
            val customInstructions = prefs.getString("chat_suggest_custom_instructions", "") ?: ""
            val customBlock = if (customInstructions.isNotEmpty()) {
                "\n■ ユーザー指定のサジェスト追加ルール・例文：\n$customInstructions\n"
            } else {
                ""
            }
            
            val jsonArray = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            for (i in history.indices) {
                val msg = history[i]
                var contentText = msg.text
                if (isSuggestEnabled && i == history.lastIndex && msg.isUser) {
                    contentText += "\n\n" +
                            "⚠️【最重要：システム指令】⚠️\n" +
                            "ユーザー（ケニーちゃん）の今回のメッセージに対して、あなたのキャラクター設定（ノアなど）を完璧に維持した魅力的な返答を生成してください。\n" +
                            "そして、必ずその「返信文の末尾」に、ケニーちゃんが次回タップして返信できるように、ケニーちゃんになりきった3つの返信候補（サジェスト）を正確に付記してください。\n" +
                            "■ サジェスト作成ルール・絶対厳守：\n" +
                            "- サジェスト機能は常時ONです。今回の返信がどのような短いセリフや特別な内容であっても、必ず最後の最後に <<<SUGGESTIONS>>> タグで囲んで3つの選択肢を出力してください。\n" +
                            "- サジェストの文字数はそれぞれ15文字以内で、短くタップしやすいものにしてください。$customBlock\n" +
                            "出力形式（この形式を必ずあなたの返信の最後の最後にそのまま正確に出力してください。余計な説明文やマークダウンは含めないでください）：\n" +
                            "<<<SUGGESTIONS>>>\n" +
                            "A: <返信案A>\n" +
                            "B: <返信案B>\n" +
                            "C: <返信案C>\n" +
                            "<<</SUGGESTIONS>>>"
                }
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

    private fun parseSuggestionsAndCleanText(node: ChatNode) {
        val rawText = node.text
        var cleanText = rawText
        var a: String? = null
        var b: String? = null
        var c: String? = null

        // 1. Tag matching using robust regex for <<<SUGGESTIONS>>>
        val tripleAngleRegex = Regex("<<<SUGGESTIONS>>>\\s*(.*?)\\s*<<</SUGGESTIONS>>>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tripleMatch = tripleAngleRegex.find(rawText)
        if (tripleMatch != null) {
            val suggestionsBlock = tripleMatch.groupValues[1].trim()
            val parsed = parseBlock(suggestionsBlock)
            a = parsed.first
            b = parsed.second
            c = parsed.third
            cleanText = rawText.replace(tripleAngleRegex, "").trim()
        } else {
            // 2. Tag matching for [SUGGESTIONS]
            val bracketRegex = Regex("\\[SUGGESTIONS\\]\\s*(.*?)\\s*\\[/SUGGESTIONS\\]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            val bracketMatch = bracketRegex.find(rawText)
            if (bracketMatch != null) {
                val suggestionsBlock = bracketMatch.groupValues[1].trim()
                val parsed = parseBlock(suggestionsBlock)
                a = parsed.first
                b = parsed.second
                c = parsed.third
                cleanText = rawText.replace(bracketRegex, "").trim()
            }
        }

        // If tag was not found as a block, or parsing failed, do a line-by-line fallback on the whole text
        if (a == null && b == null && c == null) {
            val lines = rawText.lines()
            var inBlock = false
            val cleanedLines = mutableListOf<String>()
            val blockLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("<<<SUGGESTIONS>>>", ignoreCase = true) || trimmed.contains("[SUGGESTIONS]", ignoreCase = true)) {
                    inBlock = true
                    continue
                }
                if (trimmed.contains("<<</SUGGESTIONS>>>", ignoreCase = true) || trimmed.contains("[/SUGGESTIONS]", ignoreCase = true)) {
                    inBlock = false
                    continue
                }
                if (inBlock) {
                    blockLines.add(line)
                } else {
                    cleanedLines.add(line)
                }
            }

            if (blockLines.isNotEmpty()) {
                val parsed = parseBlock(blockLines.joinToString("\n"))
                a = parsed.first
                b = parsed.second
                c = parsed.third
                cleanText = cleanedLines.joinToString("\n").trim()
            }
        }

        // If suggestions are found, populate node
        if (a != null || b != null || c != null) {
            node.suggestionA = a
            node.suggestionB = b
            node.suggestionC = c
            node.text = cleanText
        }
    }

    private fun parseBlock(block: String): Triple<String?, String?, String?> {
        var a: String? = null
        var b: String? = null
        var c: String? = null

        val lines = block.lines()
        for (line in lines) {
            val trimmed = line.trim()
            // Support patterns like: A: text, A. text, - A: text, **A**: text, 1. text, 1: text etc.
            val aMatch = Regex("^[\\s*-]*\\*?\\*?[aA1]\\*?\\*?[\\s.:：\\)-]+(.*)$").find(trimmed)
            val bMatch = Regex("^[\\s*-]*\\*?\\*?[bB2]\\*?\\*?[\\s.:：\\)-]+(.*)$").find(trimmed)
            val cMatch = Regex("^[\\s*-]*\\*?\\*?[cC3]\\*?\\*?[\\s.:：\\)-]+(.*)$").find(trimmed)

            if (aMatch != null && a == null) {
                a = aMatch.groups[1]?.value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            } else if (bMatch != null && b == null) {
                b = bMatch.groups[1]?.value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            } else if (cMatch != null && c == null) {
                c = cMatch.groups[1]?.value?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            }
        }
        return Triple(a, b, c)
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
            parseSuggestionsAndCleanText(aiNode)
        }

        // ディスクに即時保存
        ChatSessionManager.saveSessionData(context, sessionId, chatTree)
        
        // メインスレッドでリスナー通知＆クリーンアップ
        scope.launch(Dispatchers.Main) {
            listeners.forEach { 
                it.onProgress(aiNode.text, isComplete, modelName = aiNode.modelName, error = error)
            }

            if (isComplete) {
                if (error == null && pendingYenCost > 0) {
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    
                    // wallet_balance を優先して使用するわよ
                    val currentBalance = if (prefs.contains("wallet_balance")) {
                        prefs.getInt("wallet_balance", 0)
                    } else {
                        prefs.getInt("magic_stones", 0) * 10
                    }
                    
                    val newBalance = if (currentBalance >= pendingYenCost) {
                        saveUnpaidChatCount(context, 0) // 支払えたら無課金カウントリセット
                        currentBalance - pendingYenCost
                    } else {
                        // 払えなかったら無課金カウントを増やすわよ！
                        val count = prefs.getInt("unpaid_chat_count", 0)
                        saveUnpaidChatCount(context, count + 1)
                        0
                    }
                    
                    prefs.edit().apply {
                        putInt("wallet_balance", newBalance)
                        putInt("magic_stones", newBalance / 10) // 互換性のための同期
                        apply()
                    }
                    Log.d("ChatGenerationManager", "Successfully deducted $pendingYenCost Yen. New balance: $newBalance")
                }
                pendingYenCost = 0
                isGenerating = false
                activeSessionId = null
                activeAiNodeId = null
                LlmForegroundService.stop(context)
            }
        }
    }

    private fun saveUnpaidChatCount(context: Context, count: Int) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
        prefs.putInt("unpaid_chat_count", count)
        prefs.apply()
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
