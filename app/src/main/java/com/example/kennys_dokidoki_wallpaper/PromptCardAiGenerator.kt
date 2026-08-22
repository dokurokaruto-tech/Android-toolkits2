package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PromptCardLlmProvider { XAI, OPENROUTER, LOCAL }

data class PromptCardLlmChoice(
    val key: String,
    val provider: PromptCardLlmProvider,
    val modelId: String,
    val displayName: String,
    val contextLength: Int = 0,
    val isFree: Boolean = false,
    val pricePerMillion: Double = 0.0
)

/** Natural-language -> Stable Diffusion prompt conversion shared by prompt-card editing UI. */
object PromptCardAiGenerator {
    private const val PREF_CHOICE = "prompt_card_ai_llm_choice"
    private const val GROK_MODEL = "grok-4-1-fast-non-reasoning"

    private val systemPrompt = """
        You convert Japanese or English natural-language image descriptions into MINIMAL Stable Diffusion prompts.
        Return exactly one JSON object and no markdown:
        {"main_prompt":"comma-separated English visual tags","negative_prompt":"comma-separated English negative tags"}

        ABSOLUTE RULE: output only the smallest set of essential visual elements explicitly stated by the user.
        Translate stated nouns, attributes, actions, and relationships. Do not enrich, beautify, or complete the scene.
        Never infer time of day, weather, location, background, lighting, camera, composition, art style, mood, or colors unless explicitly stated.
        Never add quality boilerplate such as masterpiece, best quality, high quality, detailed, 8k, 4k, HDR, sharp focus, cinematic, or photorealistic unless that exact idea was explicitly requested.
        Keep negative_prompt empty unless the user explicitly says to exclude or avoid something, or asks to preserve an existing negative prompt.
        Preserve existing LoRA tokens or weighted syntax only when an existing prompt is supplied.

        Minimal examples:
        User: 馬
        Output: {"main_prompt":"horse","negative_prompt":""}
        User: 赤い馬が走っている
        Output: {"main_prompt":"red horse, running","negative_prompt":""}
        Do not turn 馬 into "horse, morning, field, sunlight, masterpiece, 8k".
        Never add commentary, explanations, or JSON fields other than main_prompt and negative_prompt.
    """.trimIndent()

    fun loadCachedChoices(context: Context): List<PromptCardLlmChoice> {
        val result = mutableListOf(
            PromptCardLlmChoice(
                key = "XAI|$GROK_MODEL",
                provider = PromptCardLlmProvider.XAI,
                modelId = GROK_MODEL,
                displayName = "xAI • Grok 4.1 Fast",
                pricePerMillion = -1.0
            )
        )
        LocalModelManager.getAllModels(context).forEach { model ->
            result += PromptCardLlmChoice(
                key = "LOCAL|${model.name}",
                provider = PromptCardLlmProvider.LOCAL,
                modelId = model.name,
                displayName = "端末 • ${model.name}",
                isFree = true
            )
        }
        result += parseOpenRouterChoices(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("cached_openrouter_models", null)
        )
        return result.distinctBy { it.key }
    }

    suspend fun refreshOpenRouterChoices(context: Context): List<PromptCardLlmChoice> = withContext(Dispatchers.IO) {
        val connection = (URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("OpenRouterモデル一覧 HTTP ${connection.responseCode}")
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("cached_openrouter_models", response).apply()
            val fixedAndLocal = loadCachedChoices(context).filter { it.provider != PromptCardLlmProvider.OPENROUTER }
            (fixedAndLocal + parseOpenRouterChoices(response)).distinctBy { it.key }
        } finally {
            connection.disconnect()
        }
    }

    fun selectedChoice(context: Context): PromptCardLlmChoice {
        val choices = loadCachedChoices(context)
        val selectedKey = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString(PREF_CHOICE, null)
        choices.firstOrNull { it.key == selectedKey }?.let { return it }

        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getString("xai_api_key", "").isNullOrBlank()) return choices.first()
        val openRouterModel = settings.getString("chat_openrouter_model", null)
        choices.firstOrNull {
            it.provider == PromptCardLlmProvider.OPENROUTER && it.modelId == openRouterModel
        }?.let { return it }
        choices.firstOrNull { it.provider == PromptCardLlmProvider.LOCAL }?.let { return it }
        return choices.firstOrNull { it.provider == PromptCardLlmProvider.OPENROUTER } ?: choices.first()
    }

    fun saveSelectedChoice(context: Context, choice: PromptCardLlmChoice) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString(PREF_CHOICE, choice.key).apply()
        if (choice.provider == PromptCardLlmProvider.OPENROUTER) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("chat_openrouter_model", choice.modelId).apply()
        } else if (choice.provider == PromptCardLlmProvider.LOCAL) {
            LocalModelManager.setSelectedModel(context, choice.modelId)
        }
    }

    suspend fun generate(
        context: Context,
        choice: PromptCardLlmChoice,
        naturalLanguage: String,
        existingMain: String,
        existingNegative: String,
        useExisting: Boolean
    ): PromptCardAiResult {
        require(naturalLanguage.isNotBlank()) { "自然言語の説明を入力してください" }
        val userPrompt = buildUserPrompt(naturalLanguage, existingMain, existingNegative, useExisting)
        val raw = when (choice.provider) {
            PromptCardLlmProvider.XAI -> generateXai(context, choice.modelId, userPrompt)
            PromptCardLlmProvider.OPENROUTER -> generateOpenRouter(context, choice.modelId, userPrompt)
            PromptCardLlmProvider.LOCAL -> generateLocal(context, choice.modelId, userPrompt)
        }
        val minimized = PromptCardPromptMinimalizer.minimize(
            result = PromptCardAiResponseParser.parse(raw),
            naturalLanguage = naturalLanguage,
            existingNegative = existingNegative,
            useExisting = useExisting
        )
        require(minimized.mainPrompt.isNotBlank()) {
            "重要要素を抽出できませんでした。説明を少し具体的にしてください"
        }
        return minimized
    }

    internal fun buildUserPrompt(
        naturalLanguage: String,
        existingMain: String,
        existingNegative: String,
        useExisting: Boolean
    ): String = buildString {
        appendLine("Convert this description using only its explicitly stated essential elements.")
        appendLine("Do not add plausible context or generic quality tags.")
        appendLine("Description:")
        appendLine(naturalLanguage.trim())
        if (useExisting) {
            appendLine()
            appendLine("Existing main prompt to preserve and improve:")
            appendLine(existingMain.trim().ifEmpty { "(empty)" })
            appendLine("Existing negative prompt:")
            appendLine(existingNegative.trim().ifEmpty { "(empty)" })
        }
    }

    private suspend fun generateXai(context: Context, model: String, userPrompt: String): String {
        var key = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("xai_api_key", "")?.trim().orEmpty()
        if (key.isBlank()) throw IllegalStateException("xAI APIキーが設定されていません")
        if (!key.startsWith("xai-")) key = "xai-$key"
        return cloudCompletion("https://api.x.ai/v1/chat/completions", key, model, userPrompt)
    }

    private suspend fun generateOpenRouter(context: Context, model: String, userPrompt: String): String {
        val key = OpenRouterManager.getActiveApiKey(context)
            ?: throw IllegalStateException("OpenRouter APIキーが設定されていません")
        val result = cloudCompletion("https://openrouter.ai/api/v1/chat/completions", key, model, userPrompt)
        OpenRouterManager.incrementUsage(context, key)
        return result
    }

    private suspend fun cloudCompletion(url: String, key: String, model: String, userPrompt: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", model)
                put("temperature", 0.0)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                })
            }
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                val code = connection.responseCode
                val source = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = source?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IOException("LLM HTTP $code: ${text.take(500)}")
                JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun generateLocal(context: Context, modelName: String, userPrompt: String): String {
        val model = LocalModelManager.getAllModels(context).firstOrNull { it.name == modelName }
            ?: throw IllegalStateException("ローカルモデル『$modelName』が見つかりません")
        LocalModelManager.setSelectedModel(context, model.name)
        val loadError = LlmInferenceEngine.loadModelDetailed(context, model.file)
        if (loadError != null) throw IllegalStateException(loadError)

        LlmForegroundService.start(context)
        return try {
            val localPrompt = """
                <|im_start|>system
                $systemPrompt
                <|im_end|>
                <|im_start|>user
                $userPrompt
                <|im_end|>
                <|im_start|>assistant
            """.trimIndent()
            coroutineScope {
                suspendCancellableCoroutine { continuation ->
                    val output = StringBuilder()
                    val completed = AtomicBoolean(false)
                    launch(Dispatchers.IO) {
                        LlmInferenceEngine.generate(
                            prompt = localPrompt,
                            onToken = { token -> synchronized(output) { output.append(token) } },
                            onComplete = {
                                if (completed.compareAndSet(false, true) && continuation.isActive) {
                                    val text = synchronized(output) { output.toString() }
                                    continuation.resume(text)
                                }
                            },
                            onError = { message ->
                                if (completed.compareAndSet(false, true) && continuation.isActive) {
                                    continuation.resumeWithException(IllegalStateException(message))
                                }
                            }
                        )
                    }
                }
            }
        } finally {
            LlmForegroundService.stop(context)
        }
    }

    private fun parseOpenRouterChoices(json: String?): List<PromptCardLlmChoice> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val data = JSONObject(json).getJSONArray("data")
            buildList {
                for (index in 0 until data.length()) {
                    val model = data.getJSONObject(index)
                    val pricing = model.optJSONObject("pricing")
                    val promptPrice = pricing?.optDouble("prompt", 0.0) ?: 0.0
                    val completionPrice = pricing?.optDouble("completion", 0.0) ?: 0.0
                    val free = promptPrice == 0.0 && completionPrice == 0.0
                    val id = model.getString("id")
                    add(
                        PromptCardLlmChoice(
                            key = "OPENROUTER|$id",
                            provider = PromptCardLlmProvider.OPENROUTER,
                            modelId = id,
                            displayName = "OpenRouter • ${model.optString("name", id)}",
                            contextLength = model.optInt("context_length", 0),
                            isFree = free,
                            pricePerMillion = promptPrice * 1_000_000.0
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
