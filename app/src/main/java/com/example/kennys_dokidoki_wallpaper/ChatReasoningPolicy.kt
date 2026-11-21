package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推論（Thinking / Reasoning）対応モデルの判定・推論設定・思考過程の抽出と開閉状態管理。
 *
 *   モデル情報 ──▶ supports() ─┬─ false ──▶ 追加パラメータなし
 *                             └─ true  ──▶ モデル選択画面のON/OFF指定に従う
 *
 *   生成開始(思考到着) ──▶ 自動展開 (expanded = true)
 *   推論終了(本文到着/完了) ──▶ 自動格納 (expanded = false)
 *   ユーザータップ ──▶ 手動で表示/非表示を切替
 */
internal object ChatReasoningPolicy {
    const val PREF_KEY = "chat_reasoning_enabled"

    private const val ENGINE_CLOUD = "CLOUD"
    private const val ENGINE_LOCAL = "LOCAL"
    private const val PROVIDER_OPENROUTER = "OPENROUTER"
    private const val CACHED_MODELS_KEY = "cached_openrouter_models"
    private const val PARAM_REASONING = "reasoning"
    private const val PARAM_INCLUDE_REASONING = "include_reasoning"
    private const val THINK_OPEN = "<think>"
    private const val THINK_CLOSE = "</think>"
    private const val MS_PER_SECOND = 1000L

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
        """<think>(.*?)</think>\s*""",
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

    enum class Phase {
        NONE,
        THINKING,
        FINISHED
    }

    enum class AccordionState {
        EXPANDED,
        COLLAPSED
    }

    data class ExtractedReasoning(
        val reasoning: String,
        val visibleContent: String,
        val phase: Phase
    )

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

    /** 思考ヘッダー文言（例: "Thinking..." / "Thought for 3 seconds"）。 */
    fun headerLabel(phase: Phase, durationMs: Long?): String {
        if (phase == Phase.THINKING) {
            return "Thinking..."
        }
        val seconds = ((durationMs ?: 0L) + 500L) / MS_PER_SECOND
        val clamped = seconds.coerceAtLeast(1L)
        return if (durationMs == null || durationMs <= 0L) {
            "Thought process"
        } else if (clamped == 1L) {
            "Thought for 1 second"
        } else {
            "Thought for $clamped seconds"
        }
    }

    /**
     * 思考ブロックの展開状態を決定する。
     * 手動操作があれば最優先し、未操作なら推論中は展開・推論終了後は自動で折りたたむ。
     */
    fun resolveAccordion(phase: Phase, manualOverride: AccordionState?): AccordionState {
        if (manualOverride != null) {
            return manualOverride
        }
        return if (phase == Phase.THINKING) {
            AccordionState.EXPANDED
        } else {
            AccordionState.COLLAPSED
        }
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
            return containsReasoningParam(params)
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

    /** OpenRouterリクエストJSONに推論ON/OFF指定を付与する。ON時は推論過程も受信する。 */
    fun applyOpenRouter(
        prefs: SharedPreferences,
        modelId: String,
        request: JSONObject,
        purpose: RequestPurpose = RequestPurpose.CHAT_REPLY
    ) {
        val cached = prefs.getString(CACHED_MODELS_KEY, null)
        if (!supportsOpenRouter(modelId, cached)) {
            return
        }
        val enabled = Mode.fromPrefs(prefs) == Mode.ENABLED
        val exclude = !enabled || purpose == RequestPurpose.BACKGROUND_HELPER
        request.put(
            PARAM_REASONING,
            JSONObject()
                .put("enabled", enabled)
                .put("exclude", exclude)
        )
    }

    enum class RequestPurpose {
        CHAT_REPLY,
        BACKGROUND_HELPER
    }

    enum class StreamStage {
        STREAMING,
        COMPLETED
    }

    /** OpenRouter SSEの delta オブジェクトから推論トークン文字列を取り出す。 */
    fun reasoningDeltaFromChunk(delta: JSONObject): String {
        if (delta.has("reasoning") && !delta.isNull("reasoning")) {
            val text = delta.optString("reasoning", "")
            if (text.isNotEmpty()) {
                return text
            }
        }
        if (delta.has("reasoning_content") && !delta.isNull("reasoning_content")) {
            val text = delta.optString("reasoning_content", "")
            if (text.isNotEmpty()) {
                return text
            }
        }
        val details = delta.optJSONArray("reasoning_details") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            val text = when {
                !item.isNull("text") -> item.optString("text", "")
                !item.isNull("summary") -> item.optString("summary", "")
                else -> ""
            }
            sb.append(text)
        }
        return sb.toString()
    }

    /**
     * ストリームされた推論文字列と本文文字列（`<think>...</think>` 混在可）を統合・分離する。
     */
    fun extract(
        apiReasoning: String,
        rawContent: String,
        stage: StreamStage
    ): ExtractedReasoning {
        val thoughts = mutableListOf<String>()
        if (apiReasoning.isNotBlank()) {
            thoughts.add(apiReasoning.trim())
        }

        closedThinkBlockRegex.findAll(rawContent).forEach { match ->
            val inner = match.groupValues[1].trim()
            if (inner.isNotEmpty()) {
                thoughts.add(inner)
            }
        }

        val withoutClosed = rawContent.replace(closedThinkBlockRegex, "")
        val openIdx = withoutClosed.indexOf(THINK_OPEN, ignoreCase = true)
        val hasUnclosedThink = openIdx >= 0
        val visible: String
        if (hasUnclosedThink) {
            val before = withoutClosed.substring(0, openIdx).trimEnd()
            val unclosed = withoutClosed.substring(openIdx + THINK_OPEN.length).trim()
            if (unclosed.isNotEmpty()) {
                thoughts.add(unclosed)
            }
            visible = before
        } else {
            visible = withoutClosed
        }

        val combinedReasoning = thoughts.joinToString("\n\n").trim()
        if (combinedReasoning.isEmpty() && !hasUnclosedThink) {
            return ExtractedReasoning("", visible, Phase.NONE)
        }

        val stillThinking = stage == StreamStage.STREAMING && (hasUnclosedThink || visible.isBlank())
        val phase = if (stillThinking) {
            Phase.THINKING
        } else {
            Phase.FINISHED
        }
        return ExtractedReasoning(combinedReasoning, visible, phase)
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
        return extract("", raw, StreamStage.COMPLETED).visibleContent
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
