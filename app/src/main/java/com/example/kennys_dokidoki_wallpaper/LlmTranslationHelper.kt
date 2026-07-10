package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * キャラクター定義データをLLMでまとめて翻訳・リライトするためのヘルパーよ♡
 */
object LlmTranslationHelper {
    private const val TAG = "LlmTranslationHelper"

    suspend fun translateCharacter(context: Context, charData: CharacterData): CharacterData = withContext(Dispatchers.IO) {
        val inputJson = JSONObject().apply {
            put("name", charData.name)
            put("description", charData.description)
            put("personality", charData.personality)
            put("firstMes", charData.firstMes)
            put("scenario", charData.scenario)
            put("systemPrompt", charData.systemPrompt)
        }

        val systemPrompt = "You are a professional character localization assistant. " +
                "Translate and localize the following character configuration JSON from English to natural, rich, and context-aware Japanese. " +
                "Ensure that character names, personality descriptions, greeting first messages (firstMes), scenarios, and prompts sound highly engaging, stylized, natural, and creative in Japanese (suitable for a chat roleplay application). " +
                "Do NOT use stiff or literal translation. Re-write expressions so that the character's tone, gender, and nuances are beautifully conveyed. " +
                "Preserve the exact JSON keys (\"name\", \"description\", \"personality\", \"firstMes\", \"scenario\", \"systemPrompt\"). " +
                "Do NOT include any explanations, introduction, markdown code blocks (such as ```json), or extra text. Output ONLY the raw JSON object."

        val userPrompt = inputJson.toString()

        var responseText = ""
        var success = false

        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val provider = prefs.getString("tag_ai_provider", "XAI") ?: "XAI"

        // 設定されている優先度の高いAPIから順に試すわね
        val providersToTry = if (provider == "OPENROUTER") {
            listOf("OPENROUTER", "XAI")
        } else {
            listOf("XAI", "OPENROUTER")
        }

        for (p in providersToTry) {
            try {
                if (p == "OPENROUTER") {
                    val apiKey = OpenRouterManager.getActiveApiKey(context)
                    if (apiKey != null) {
                        val modelName = prefs.getString("chat_openrouter_model", "deepseek/deepseek-v4-flash:free") ?: "deepseek/deepseek-v4-flash:free"
                        Log.d(TAG, "Translating via OpenRouter using model: $modelName")
                        val url = URL("https://openrouter.ai/api/v1/chat/completions")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Authorization", "Bearer $apiKey")
                            setRequestProperty("Content-Type", "application/json")
                            setRequestProperty("HTTP-Referer", "https://github.com/example/android-toolkits")
                            setRequestProperty("X-Title", "Android Toolkits")
                            connectTimeout = 30000
                            readTimeout = 30000
                            doOutput = true
                        }

                        val messages = JSONArray().apply {
                            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                            put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                        }
                        val requestBody = JSONObject().apply {
                            put("model", modelName)
                            put("messages", messages)
                            put("stream", false)
                        }

                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

                        if (conn.responseCode == 200) {
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            responseText = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                            OpenRouterManager.incrementUsage(context, apiKey)
                            success = true
                            Log.d(TAG, "OpenRouter translation succeeded!")
                            break
                        } else {
                            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            Log.e(TAG, "OpenRouter translation failed: ${conn.responseCode} - $errText")
                        }
                    }
                } else if (p == "XAI") {
                    var xaiApiKey = prefs.getString("xai_api_key", "")?.trim() ?: ""
                    if (xaiApiKey.isNotEmpty()) {
                        if (!xaiApiKey.startsWith("xai-")) xaiApiKey = "xai-$xaiApiKey"
                        val modelName = "grok-4-1-fast-non-reasoning"
                        Log.d(TAG, "Translating via xAI Grok")
                        val url = URL("https://api.x.ai/v1/chat/completions")
                        val conn = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            setRequestProperty("Authorization", "Bearer $xaiApiKey")
                            setRequestProperty("Content-Type", "application/json")
                            connectTimeout = 30000
                            readTimeout = 30000
                            doOutput = true
                        }

                        val messages = JSONArray().apply {
                            put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                            put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
                        }
                        val requestBody = JSONObject().apply {
                            put("model", modelName)
                            put("messages", messages)
                            put("stream", false)
                        }

                        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(requestBody.toString()) }

                        if (conn.responseCode == 200) {
                            val response = conn.inputStream.bufferedReader().use { it.readText() }
                            responseText = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                            success = true
                            Log.d(TAG, "xAI Grok translation succeeded!")
                            break
                        } else {
                            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                            Log.e(TAG, "xAI Grok translation failed: ${conn.responseCode} - $errText")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed with provider $p", e)
            }
        }

        // ローカルLLMフォールバック
        if (!success && LlmInferenceEngine.isModelLoaded()) {
            try {
                Log.d(TAG, "Attempting fallback to local LLM inference...")
                val fullPrompt = "$systemPrompt\n\nInput JSON:\n$userPrompt"
                var localResult = ""
                LlmInferenceEngine.generate(
                    prompt = fullPrompt,
                    onToken = { localResult += it },
                    onComplete = {},
                    onError = { Log.e(TAG, "Local inference error during translation: $it") }
                )
                if (localResult.isNotEmpty()) {
                    responseText = localResult
                    success = true
                    Log.d(TAG, "Local translation succeeded!")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local translation fallback failed", e)
            }
        }

        if (success && responseText.isNotEmpty()) {
            try {
                // クリーンアップ (```json などのMarkdown囲みがあれば削除)
                var cleanJson = responseText.trim()
                if (cleanJson.startsWith("```")) {
                    cleanJson = cleanJson.substringAfter("```json").substringAfter("```").substringBeforeLast("```").trim()
                }
                val resultObj = JSONObject(cleanJson)
                return@withContext CharacterData(
                    name = resultObj.optString("name", charData.name),
                    description = resultObj.optString("description", charData.description),
                    personality = resultObj.optString("personality", charData.personality),
                    firstMes = resultObj.optString("firstMes", charData.firstMes),
                    scenario = resultObj.optString("scenario", charData.scenario),
                    systemPrompt = resultObj.optString("systemPrompt", charData.systemPrompt)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing LLM translation response JSON: $responseText", e)
            }
        }

        // 翻訳に失敗した、またはAPI Key未設定ならそのまま返すわ
        return@withContext charData
    }
}
