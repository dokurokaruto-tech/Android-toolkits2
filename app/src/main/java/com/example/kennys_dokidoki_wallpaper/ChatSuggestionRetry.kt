package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlinx.coroutines.launch

/**
 * 返信本文はあるのにサジェストだけ欠けたとき、会話履歴を渡して候補だけ作り直す。
 *
 *   history(直近10件) ──┐
 *   system + rules  ───┼─▶ LLM(現在のエンジン) ─▶ <<<SUGGESTIONS>>> ブロック ─▶ parse
 *   retry指示       ───┘
 */
internal object ChatSuggestionRetry {
    private const val HTTP_OK = 200
    private const val RETRY_INSTRUCTION =
        "上の会話の流れを踏まえ、直前のアシスタント発言に対するユーザーの返信候補だけを出力してください。" +
            "本文や前置きは一切書かず、<<<SUGGESTIONS>>> ブロックのみを出力すること。"

    /** 失敗時は null。 */
    suspend fun generate(
        context: Context,
        systemPrompt: String,
        history: List<ChatNode>,
        rules: String
    ): String? {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val engine = prefs.getString("chat_llm_engine", "CLOUD") ?: "CLOUD"
        val system = systemPrompt + "\n\n" + rules
        val messages = history.map { it.copy() } + ChatNode(text = RETRY_INSTRUCTION, isUser = true)
        return runCatching {
            if (engine == "LOCAL") local(system, messages) else cloud(context, system, messages)
        }.getOrNull()
    }

    fun parse(raw: String?): ChatSuggestionParser.Result? {
        if (raw.isNullOrBlank()) {
            return null
        }
        val parsed = ChatSuggestionParser.parse(raw)
        return parsed.takeIf { it.hasSuggestions }
    }

    private suspend fun cloud(context: Context, system: String, messages: List<ChatNode>): String? =
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
            val apiKey: String
            val apiUrl: String
            val model: String
            if (provider == "OPENROUTER") {
                apiKey = OpenRouterManager.getActiveApiKey(context) ?: return@withContext null
                apiUrl = "https://openrouter.ai/api/v1/chat/completions"
                model = prefs.getString("chat_openrouter_model", null) ?: return@withContext null
            } else {
                var key = prefs.getString("xai_api_key", "")?.trim().orEmpty()
                if (key.isEmpty()) return@withContext null
                if (!key.startsWith("xai-")) key = "xai-$key"
                apiKey = key
                apiUrl = "https://api.x.ai/v1/chat/completions"
                model = "grok-2-1212"
            }

            val body = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", system))
                    messages.forEach {
                        put(JSONObject().put("role", if (it.isUser) "user" else "assistant").put("content", it.text))
                    }
                })
                if (provider == "OPENROUTER") {
                    OpenRouterRouting.apply(prefs, model, this)
                }
            }

            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }
                if (conn.responseCode != HTTP_OK) return@withContext null

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                if (provider == "OPENROUTER" && content.isNotEmpty()) {
                    OpenRouterManager.countFreeUsage(context, apiKey, model)
                }
                content
            } finally {
                conn.disconnect()
            }
        }

    private suspend fun local(system: String, messages: List<ChatNode>): String? {
        if (!LlmInferenceEngine.isModelLoaded()) {
            return null
        }
        val prompt = LlmInferenceEngine.buildChatPrompt(system, messages)
        return suspendCancellableCoroutine { cont ->
            val sb = StringBuilder()
            CoroutineScope(Dispatchers.IO).launch {
                LlmInferenceEngine.generate(
                    prompt = prompt,
                    onToken = { sb.append(it) },
                    onComplete = { if (cont.isActive) cont.resume(sb.toString()) },
                    onError = { if (cont.isActive) cont.resume(null) }
                )
            }
        }
    }
}
