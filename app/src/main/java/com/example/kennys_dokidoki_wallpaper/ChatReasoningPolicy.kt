package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推論（Thinking / Reasoning）対応モデルの判定と、チャット画面の推論ON/OFF制御。
 *
 *   モデル情報 ──▶ supports() ─┬─ false ──▶ トグル非表示 / 追加パラメータなし
 *                             └─ true  ──▶ トグル表示   ──▶ applyCloud() / buildLocalPrompt()
 */
internal object ChatReasoningPolicy {
    const val PREF_KEY = "chat_reasoning_enabled"
    const val LABEL_ON = "🧠 推論 ON"
    const val LABEL_OFF = "🧠 推論 OFF"

    private const val ENGINE_CLOUD = "CLOUD"
    private const val ENGINE_LOCAL = "LOCAL"
    private const val PROVIDER_OPENROUTER = "OPENROUTER"
    private const val CACHED_MODELS_KEY = "cached_openrouter_models"
    private const val PARAM_REASONING = "reasoning"
    private const val PARAM_INCLUDE_REASONING = "include_reasoning"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"

    private val reasoningIdHints = listOf(
        "deepseek-r1",
        "deepseek/deepseek-r1",
        "qwq",
        "marco-o1",
        "skywork-o1",
        "gemini-2.5-pro",
        "gemini-2.5-flash",
        "claude-3.7-sonnet",
        "claude-3-7-sonnet",
        "claude-sonnet-4",
        "claude-opus-4",
        "grok-3-mini"
    )

    private val reasoningTokenRegex = Regex(
        """(?:^|[/\-_:.])(r1|o1|o3|o4|qwq|thinking|reasoning|reasoner)(?:$|[/\-_:.0-9])""",
        RegexOption.IGNORE_CASE
    )

    private val nonReasoningHintRegex = Regex(
        """non[-_]?reasoning|no[-_]?think""",
        RegexOption.IGNORE_CASE
    )

    private val closedThinkBlockRegex = Regex(
        """<think>.*?</think>\s*""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    enum class Capability {
        SUPPORTED,
        UNSUPPORTED
    }

    enum class Mode {
        ENABLED,
        DISABLED;

        companion object {
            fun fromPrefs(prefs: SharedPreferences): Mode =
                if (prefs.getBoolean(PREF_KEY, true)) {
                    ENABLED
                } else {
                    DISABLED
                }
        }
    }

    fun toggle(prefs: SharedPreferences): Mode {
        val next = if (Mode.fromPrefs(prefs) == Mode.ENABLED) {
            Mode.DISABLED
        } else {
            Mode.ENABLED
        }
        save(prefs, next)
        return next
    }

    fun save(prefs: SharedPreferences, mode: Mode) {
        prefs.edit().putBoolean(PREF_KEY, mode == Mode.ENABLED).apply()
    }

    fun badgeLabel(mode: Mode): String =
        if (mode == Mode.ENABLED) {
            LABEL_ON
        } else {
            LABEL_OFF
        }

    /** 現在選択中のチャットエンジン・モデルが推論切替に対応しているか。 */
    fun isCurrentTargetSupported(
        prefs: SharedPreferences,
        defaultOpenRouterModel: String,
        localModelName: String?
    ): Boolean {
        val engine = prefs.getString("chat_llm_engine", ENGINE_CLOUD) ?: ENGINE_CLOUD
        if (engine == ENGINE_LOCAL) {
            return localModelName != null && supportsByNameOrId(localModelName)
        }
        val provider = prefs.getString("chat_cloud_provider", "GROK") ?: "GROK"
        if (provider != PROVIDER_OPENROUTER) {
            return false
        }
        val modelId = prefs.getString("chat_openrouter_model", defaultOpenRouterModel)
            ?: defaultOpenRouterModel
        val cached = prefs.getString(CACHED_MODELS_KEY, null)
        return supportsOpenRouter(modelId, cached)
    }

    /** OpenRouterモデルが推論制御に対応しているか。キャッシュのsupported_parametersを優先する。 */
    fun supportsOpenRouter(modelId: String, cachedModelsJson: String?): Boolean {
        if (modelId.isBlank() || nonReasoningHintRegex.containsMatchIn(modelId)) {
            return false
        }
        val fromMeta = supportsInCachedMetadata(modelId, cachedModelsJson)
        if (fromMeta != null) {
            return fromMeta
        }
        return supportsByNameOrId(modelId)
    }

    /** 単一のOpenRouterモデルJSONオブジェクトから推論対応を判定する。 */
    fun capabilityOf(modelObj: JSONObject): Capability =
        if (supportsModelObject(modelObj)) {
            Capability.SUPPORTED
        } else {
            Capability.UNSUPPORTED
        }

    /** 単一のOpenRouterモデルJSONオブジェクトから推論対応を判定する。 */
    fun supportsModelObject(modelObj: JSONObject): Boolean {
        val id = modelObj.optString("id", "")
        if (id.isBlank() || nonReasoningHintRegex.containsMatchIn(id)) {
            return false
        }
        val params = modelObj.optJSONArray("supported_parameters")
        if (params != null) {
            if (containsReasoningParam(params)) {
                return true
            }
            return false
        }
        val pricing = modelObj.optJSONObject("pricing")
        if (hasPositivePrice(pricing?.optString("internal_reasoning"))) {
            return true
        }
        val name = modelObj.optString("name", "")
        return supportsByNameOrId(id) || supportsByNameOrId(name)
    }

    /** モデル名・ID文字列のヒューリスティック判定（ローカルGGUFやメタデータ未取得時用）。 */
    fun supportsByNameOrId(value: String): Boolean {
        if (value.isBlank() || nonReasoningHintRegex.containsMatchIn(value)) {
            return false
        }
        val lower = value.lowercase()
        if (reasoningIdHints.any { lower.contains(it) }) {
            return true
        }
        if (reasoningTokenRegex.containsMatchIn(lower)) {
            return true
        }
        if (lower.contains("qwen3") || lower.contains("qwen-3") || lower.contains("deepseek-v3.1")) {
            return true
        }
        return false
    }

    /** OpenRouterリクエストJSONに推論ON/OFF指定を付与する。非対応モデルには何もしない。 */
    fun applyOpenRouter(
        prefs: SharedPreferences,
        modelId: String,
        request: JSONObject
    ) {
        val cached = prefs.getString(CACHED_MODELS_KEY, null)
        if (!supportsOpenRouter(modelId, cached)) {
            return
        }
        val enabled = Mode.fromPrefs(prefs) == Mode.ENABLED
        request.put(
            PARAM_REASONING,
            JSONObject()
                .put("enabled", enabled)
                .put("exclude", true)
        )
    }

    /** ローカルモデル向けに、推論OFF時は空の思考ブロックを前置して思考をスキップさせる。 */
    fun applyLocalPrompt(
        baseChatMlPrompt: String,
        modelName: String?,
        mode: Mode
    ): String {
        if (modelName == null || !supportsByNameOrId(modelName)) {
            return baseChatMlPrompt
        }
        if (mode == Mode.ENABLED) {
            return baseChatMlPrompt
        }
        return "$baseChatMlPrompt$THINK_OPEN\n$THINK_CLOSE\n"
    }

    /** ストリーム・完了テキストから `<think>...</think>` 思考ブロックを取り除く。 */
    fun stripThinkBlocks(raw: String): String {
        if (raw.isEmpty()) {
            return raw
        }
        val withoutClosed = raw.replace(closedThinkBlockRegex, "")
        val openIdx = withoutClosed.indexOf(THINK_OPEN, ignoreCase = true)
        if (openIdx < 0) {
            return withoutClosed
        }
        return withoutClosed.substring(0, openIdx).trimEnd()
    }

    private fun supportsInCachedMetadata(modelId: String, cachedJson: String?): Boolean? {
        if (cachedJson.isNullOrBlank()) {
            return null
        }
        return try {
            val data = JSONObject(cachedJson).optJSONArray("data") ?: return null
            for (index in 0 until data.length()) {
                val obj = data.optJSONObject(index) ?: continue
                if (obj.optString("id") != modelId) {
                    continue
                }
                return supportsModelObject(obj)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun containsReasoningParam(params: JSONArray): Boolean {
        for (index in 0 until params.length()) {
            val param = params.optString(index)
            if (param == PARAM_REASONING || param == PARAM_INCLUDE_REASONING) {
                return true
            }
        }
        return false
    }

    private fun hasPositivePrice(raw: String?): Boolean {
        val value = raw?.trim()?.toDoubleOrNull() ?: return false
        return value.isFinite() && value > 0.0
    }
}
