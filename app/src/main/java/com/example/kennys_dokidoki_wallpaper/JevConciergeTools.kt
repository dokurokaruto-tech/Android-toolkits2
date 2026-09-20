package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONObject

/**
 * 采配コンシェルジュの実行層。
 * 候補収集・判断APIと生成APIの呼び分け・既存データへの適用を担う。
 * UI(JevConciergeDialog)はこの層とJevConciergePolicyを通じてだけデータに触る。
 *
 * 呼び分けの鉄則:
 *   Jevモデル → 判断API(decisions)のみ。文章API(chat/completions)には絶対に送らない。
 *   文章LLM → 文章APIのみ。JevElementPolicy.writerがJevの混入を弾く。
 */

/** コンシェルジュを開いた画面。判断材料と挨拶文に使う */
internal enum class ConciergeEntry(val label: String) {
    ALL_IMAGES("全画像"),
    SETS("セット"),
    TAGS("タグ"),
    BUILDER("生成"),
    CHAT("チャット");
}


/** 生成ビルダーの状態。プリセット適用の取り消し用 */
internal data class ConciergeBuilderState(
    val selection: Map<String, Int>,
    val random: Set<String>,
    val width: Int,
    val height: Int,
    val steps: Int,
    val batch: Int,
    val sampler: String
)

/** 画像一覧の絞り込み状態。絞り込みの取り消し用 */
internal data class ConciergeFilterState(val target: String?, val has: Boolean)

/**
 * 画面側への実行口。適用・選択・画面遷移はホストが担う。
 * すべてメインスレッドで呼ぶこと。
 */
internal interface ConciergeHost {
    fun refreshBuilder()
    fun refreshTags()
    fun applyPreset(preset: Preset)
    fun selectCards(ids: Collection<String>, mode: CardSelectionMode)
    fun filterImages(tag: String)
    fun startGeneration()
    fun selectionSnapshot(): Map<String, Int>
    fun restoreSelection(levels: Map<String, Int>)
    fun builderSnapshot(): ConciergeBuilderState
    fun restoreBuilder(state: ConciergeBuilderState)
    fun filterSnapshot(): ConciergeFilterState
    fun setFilter(target: String?, has: Boolean)
    fun openBulkThumbnails(kind: ThumbKind, category: String, onDone: (Int) -> Unit)
}

internal object JevConciergeTools {
    data class Settings(val endpoint: String, val jev: String, val writer: String)

    /** Jevの接続先は一括生成と共有、モデルはコンシェルジュ専用の選択を使う */
    fun settings(context: Context): Settings {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return Settings(
            endpoint = JevElementPolicy.endpoint(
                prefs.getString("jev_endpoint", null) ?: JevElementPolicy.DEFAULT_ENDPOINT),
            jev = JevConciergePolicy.jevModel(
                prefs.getString(JevConciergePolicy.KEY_JEV, null) ?: JevConciergePolicy.DEFAULT_JEV),
            writer = JevElementPolicy.writer(
                prefs.getString(JevConciergePolicy.KEY_WRITER, null) ?: JevElementPolicy.DEFAULT_WRITER)
        )
    }

    fun apiKey(context: Context): String =
        OpenRouterManager.getActiveApiKey(context)
            ?: throw IllegalStateException("OpenRouter APIキーが未設定です。設定画面の『AIのAPI Keyを設定』から登録してください。")

    fun cardCandidates(): List<ConciergeCandidate> =
        JevGenieTools.cardRefs().map {
            ConciergeCandidate(it.id, it.label, "${it.category} :: ${it.mainPrompt}")
        }

    fun tagCandidates(): List<ConciergeCandidate> {
        if (!TagManager.isLoaded) {
            return emptyList()
        }
        return JevGenieTools.tagRefs().map { ConciergeCandidate(it.name, it.name, tagHint(it)) }
    }

    /** タグ候補の手がかり。性格が複数あれば名前と書き出しを添える */
    fun tagHint(ref: JevGenieTagRef): String {
        if (ref.variants.size <= 1) {
            return ref.text
        }
        val heads = ref.variants.joinToString(" ／ ") { "［${it.name}］${oneLine(it.text, 40)}" }
        return "$heads ｜ 既定: ${oneLine(ref.text, 60)}"
    }

    private fun oneLine(text: String, limit: Int): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= limit) one else one.take(limit) + "…"
    }

    /** 判断APIへ投げてanswersを取り出す。Jevの呼び出しはここだけ */
    suspend fun decide(context: Context, endpoint: String, body: JSONObject): JSONObject {
        val key = apiKey(context)
        val response = JevElementClient.post(endpoint, key, body)
        OpenRouterManager.countFreeUsage(context, key, body.optString("model"))
        return response.optJSONObject("answers")
            ?: throw IllegalStateException("判断APIの応答にanswersがありません。")
    }

    /** 文章APIへ投げて本文を取り出す。JevモデルはPolicy層で弾き済み */
    suspend fun chat(context: Context, body: JSONObject): String {
        val key = apiKey(context)
        val response = JevElementClient.post(JevElementClient.CHAT_ENDPOINT, key, body)
        OpenRouterManager.countFreeUsage(context, key, body.optString("model"))
        return response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("生成APIの応答に文章がありません。")
    }

    /** ライブなカードオブジェクトへ反映して保存する。対象が消えていたらfalse */
    fun applyCard(
        context: Context,
        target: JevGenieCardRef,
        newMain: String,
        newNegative: String
    ): Boolean = JevGenieTools.applyCard(
        context, JevGeniePlan.EditCard(target, newMain, newNegative, "")
    )

    fun applyTag(context: Context, target: JevGenieTagRef, newText: String): Boolean =
        JevGenieTools.applyTag(context, JevGeniePlan.EditTag(target, newText, ""))

    /**
     * 調査の証拠パック。選択中カード・組み立て後プロンプト・ランダム要素・生成設定を
     * その場で読み直して文章化する。組み立て規則は生成本体(GeneratedImageTagBinding)と同一。
     */
    fun builderEvidence(state: ConciergeBuilderState): String = buildString {
        val cards = PromptCardManager.promptCards.associateBy { it.id }
        val order = PromptCardManager.categoryOrder
        val selected = state.selection.keys.mapNotNull { cards[it] }
            .sortedBy { order.indexOf(it.category).let { i -> if (i >= 0) i else Int.MAX_VALUE } }
        appendLine("【選択中のカード ${selected.size}件】（強調Lv2は×1.2、Lv3は×1.6で効く）")
        if (selected.isEmpty()) {
            appendLine("（なし）")
        }
        selected.forEach { card ->
            val level = state.selection[card.id] ?: 1
            appendLine("・[${card.category}] ${card.label}（強調Lv$level）")
            appendLine("  prompt: ${card.mainPrompt.ifEmpty { "（空）" }}")
            if (card.negativePrompt.isNotEmpty()) {
                appendLine("  negative: ${card.negativePrompt}")
            }
        }
        val prompt = selected.joinToString(", ") { card ->
            when (state.selection[card.id] ?: 1) {
                2 -> "(${card.mainPrompt}:1.2)"
                3 -> "(${card.mainPrompt}:1.6)"
                else -> card.mainPrompt
            }
        }.trim()
        val negative = selected.map { it.negativePrompt }
            .filter { it.isNotEmpty() }.distinct().joinToString(", ").trim()
        appendLine("【組み立て後のプロンプト（選択分のみ。ランダム分は生成ごとに変わる）】")
        appendLine(prompt.ifEmpty { "（なし）" })
        appendLine("【組み立て後の除外プロンプト】")
        appendLine(negative.ifEmpty { "（なし）" })
        appendLine("【ランダム要素（生成ごとに変わる）】")
        if (state.random.isEmpty()) {
            appendLine("・カテゴリ抽選: なし")
        } else {
            state.random.sorted().forEach { category ->
                val pool = PromptCardManager.promptCards.filter { it.category == category }
                val names = pool.take(12).joinToString("、") { it.label }
                val extra = if (pool.size > 12) " 他${pool.size - 12}件" else ""
                appendLine("・カテゴリ抽選『$category』: 毎回1枚（候補${pool.size}件: $names$extra）")
            }
        }
        val solo = PromptCardManager.promptCards.filter { it.useIndividualRandomizer }
        if (solo.isEmpty()) {
            appendLine("・個別ランダマイザー: なし")
        } else {
            solo.forEach { appendLine("・個別ランダマイザー『${it.label}』: ${it.randomizerProbability}%で追加") }
        }
        appendLine(
            "【生成設定】${state.width}×${state.height} / steps=${state.steps} " +
                "/ batch=${state.batch} / sampler=${state.sampler}"
        )
        val tagCount = if (TagManager.isLoaded) "${TagManager.allTags.size}件" else "不明"
        appendLine(
            "【件数】カード${PromptCardManager.promptCards.size}件・タグ$tagCount" +
                "・プリセット${PresetManager.presets.size}件"
        )
    }.take(6000)

    fun generationReady(): Boolean =
        PromptCardManager.selectionLevels.isNotEmpty() ||
            PromptCardManager.randomEnabledCategories.isNotEmpty()
}
