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
    BUILDER("生成");
}


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
        return JevGenieTools.tagRefs().map { ConciergeCandidate(it.name, it.name, it.text) }
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

    fun generationReady(): Boolean =
        PromptCardManager.selectionLevels.isNotEmpty() ||
            PromptCardManager.randomEnabledCategories.isNotEmpty()
}
