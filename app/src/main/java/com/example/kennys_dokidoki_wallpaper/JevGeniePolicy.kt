package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/** カタログのプロンプトカード1件分のスナップショット */
internal data class JevGenieCardRef(
    val id: String,
    val label: String,
    val category: String,
    val mainPrompt: String,
    val negativePrompt: String
)

/** カタログのタグ1件分のスナップショット。textは既定の性格の文章 */
internal data class JevGenieTagRef(
    val name: String,
    val text: String,
    val variants: List<TagPromptVariant> = emptyList()
) {
    /** 指定の性格の文章。無ければ既定の文章 */
    fun variantText(variant: String?): String {
        if (variant == null) {
            return text
        }
        return variants.firstOrNull { it.name == variant }?.text ?: text
    }
}

/** ジーニーが提示する実行計画。Talkは編集を伴わない会話。 */
internal sealed class JevGeniePlan {
    abstract val reply: String

    data class EditCard(
        val target: JevGenieCardRef,
        val newMain: String,
        val newNegative: String,
        override val reply: String
    ) : JevGeniePlan()

    data class EditTag(
        val target: JevGenieTagRef,
        val newText: String,
        override val reply: String,
        val variant: String? = null
    ) : JevGeniePlan()

    data class Talk(override val reply: String) : JevGeniePlan()
}

/**
 * アプリ内アシスタント『ジーニー』の純ロジック層。
 * カタログ要約・リクエスト生成・応答解析のみを担当し、
 * UIはJevGenieDialog、ネットワークと適用はJevGenieToolsが担う。
 */
internal object JevGeniePolicy {
    /** 入出力ともに無料の最新版エイリアスを既定にする */
    const val DEFAULT_MODEL = "~typesafe/jev-latest"
    const val MODEL_KEY = "jev_genie_model"

    /** after内のJSONキー。比較ポップアップの表示名にも使う */
    const val FIELD_MAIN = "main_prompt"
    const val FIELD_NEGATIVE = "negative_prompt"
    const val FIELD_TEXT = "text"

    private const val TOOL_CARD = "edit_prompt_card"
    private const val TOOL_TAG = "edit_tag_prompt"
    private const val TOOL_NONE = "none"
    private const val MAX_TEXT = 20_000
    private const val CATALOG_CLIP = 400

    /** LLMに渡すシステム指示。カタログ(名称と現在の本文)を見せて編集対象を選ばせる。 */
    fun systemPrompt(cards: List<JevGenieCardRef>, tags: List<JevGenieTagRef>): String = buildString {
        appendLine("あなたは画像生成アプリ内のアシスタント『ジーニー』。ユーザーの願いを既存データの編集で叶える。")
        appendLine("応答は必ず次のJSONオブジェクト1個だけ。前後に説明やコードフェンスを付けない。")
        appendLine("""{"tool":"...","target":"対象名","reply":"ユーザーへの短い報告","after":{"main_prompt":"","negative_prompt":"","text":""}}""")
        appendLine("- $TOOL_CARD: プロンプトカードの編集。targetはカード名。after.$FIELD_MAIN 必須（Stable Diffusion向けの英語タグ、カンマ区切り）。after.$FIELD_NEGATIVE は除外タグの変更が必要な時だけ。")
        appendLine("- $TOOL_TAG: タグ文章の編集。targetはタグ名。after.$FIELD_TEXT 必須（日本語）。タグは複数の性格を持てる。性格を指定する時はtargetを「タグ名［性格名］」にする。")
        appendLine("- $TOOL_NONE: 編集ではなく会話や質問で返す時。")
        appendLine("targetはカタログ内の名前から1つだけ正確に選ぶ。新規作成や削除はできない。")
        appendLine("願いが部分的でも、意図を補完して最善の1案を出す。質問で止まらず先に案を提示する。")
        appendLine()
        appendLine("# カタログ: プロンプトカード（カテゴリ / 名前 :: 現在のmain_prompt）")
        cards.forEach { appendLine("- ${it.category} / ${it.label} :: ${clip(it.mainPrompt)}") }
        appendLine("# カタログ: タグ（名前 :: 現在の文章）")
        tags.forEach {
            val suffix = if (it.variants.size > 1) {
                " ｜ 性格(${it.variants.size}): ${it.variants.joinToString("/") { v -> v.name }}"
            } else {
                ""
            }
            appendLine("- ${it.name} :: ${clip(it.text)}$suffix")
        }
    }

    fun requestBody(
        model: String,
        system: String,
        history: List<Pair<String, String>>,
        wish: String
    ): JSONObject {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        messages.put(JSONObject().put("role", "user").put("content", wish))
        return JSONObject().put("model", model).put("temperature", 0.0).put("messages", messages)
    }

    /** 応答テキストから実行計画を復元する。対象名はカタログと正規化比較で紐付ける。 */
    fun parse(raw: String, cards: List<JevGenieCardRef>, tags: List<JevGenieTagRef>): JevGeniePlan {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "応答にJSONがありません。" }
        val json = JSONObject(cleaned.substring(start, end + 1))
        val reply = json.optString("reply").trim()
        return when (json.optString("tool", TOOL_NONE)) {
            TOOL_CARD -> cardPlan(json, cards, reply)
            TOOL_TAG -> tagPlan(json, tags, reply)
            else -> JevGeniePlan.Talk(reply.ifEmpty { "了解しました。" })
        }
    }

    private fun cardPlan(json: JSONObject, cards: List<JevGenieCardRef>, reply: String): JevGeniePlan.EditCard {
        val target = pick(json.optString("target").trim(), cards, { it.label }, "カード")
        val after = json.optJSONObject("after") ?: JSONObject()
        val newMain = text(after, FIELD_MAIN, "変更後のmain_prompt")
        // negativeの指定が無い・空の現在値は変更しない扱いにして、消し込み事故を防ぐ
        val newNegative = after.optString(FIELD_NEGATIVE, target.negativePrompt).trim()
            .ifEmpty { target.negativePrompt }
        require(newMain.length <= MAX_TEXT && newNegative.length <= MAX_TEXT) { "生成内容が長すぎます。" }
        return JevGeniePlan.EditCard(target, newMain, newNegative, reply.ifEmpty { "『${target.label}』を更新します。" })
    }

    private fun tagPlan(json: JSONObject, tags: List<JevGenieTagRef>, reply: String): JevGeniePlan.EditTag {
        val rawTarget = json.optString("target").trim()
        val variant = rawTarget.substringAfter("［", "").substringBefore("］", "").trim().ifEmpty { null }
        val tagName = if (variant == null) rawTarget else rawTarget.substringBefore("［").trim()
        val target = pick(tagName, tags, { it.name }, "タグ")
        if (variant != null) {
            require(target.variants.any { it.name == variant }) { "タグ『${target.name}』に『$variant』という性格がありません。" }
        }
        val after = json.optJSONObject("after") ?: JSONObject()
        val newText = text(after, FIELD_TEXT, "変更後のtext")
        val title = if (variant == null) "タグ『${target.name}』" else "タグ『${target.name}』［$variant］"
        return JevGeniePlan.EditTag(target, newText, reply.ifEmpty { "${title}を更新します。" }, variant)
    }

    private fun <T> pick(key: String, list: List<T>, name: (T) -> String, kind: String): T {
        require(key.isNotEmpty()) { "${kind}名が指定されていません。" }
        return list.firstOrNull { JevElementPolicy.sameName(name(it), key) }
            ?: list.firstOrNull { name(it).contains(key, ignoreCase = true) }
            ?: throw IllegalArgumentException("カタログに『$key』という${kind}が見つかりません。")
    }

    private fun text(after: JSONObject, key: String, label: String): String {
        val value = after.optString(key).trim()
        require(value.isNotEmpty()) { "${label}が空です。" }
        require(value.length <= MAX_TEXT) { "${label}が長すぎます。" }
        return value
    }

    private fun clip(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= CATALOG_CLIP) oneLine else oneLine.take(CATALOG_CLIP) + "…"
    }
}
