package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 要素の一括生成で必要な候補収集・API整形・保存を画面から切り分ける。 */
internal object JevElementService {
    private const val FALLBACK_CARD_CATEGORY = "未分類"
    private const val FALLBACK_TAG_CATEGORY = "その他"

    data class Settings(val endpoint: String, val jev: String, val writer: String, val instruction: String)

    fun settings(context: Context): Settings {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return Settings(
            endpoint = JevElementPolicy.endpoint(
                prefs.getString("jev_endpoint", null) ?: JevElementPolicy.DEFAULT_ENDPOINT),
            jev = JevElementPolicy.model(
                prefs.getString("jev_model", null) ?: JevElementPolicy.DEFAULT_JEV),
            writer = JevElementPolicy.writer(
                prefs.getString("jev_writer_model", null) ?: JevElementPolicy.DEFAULT_WRITER),
            instruction = prefs.getString("jev_writer_instruction", null)
                ?.takeIf { it.isNotBlank() } ?: JevElementPolicy.DEFAULT_INSTRUCTION)
    }

    fun catalog(): ElementCatalog {
        require(TagManager.isLoaded) { "タグを読み込み中です。タブを開き直してください。" }
        val cards = PromptCardManager.categoryOrder.map { it.trim() }.filter { it.isNotEmpty() }
        val tags = TagManager.categories.map { it.name }.filter { it.isNotEmpty() }
        return ElementCatalog(cards.distinct(), tags.distinct())
    }

    /** カテゴリーが片方でも空だと選択肢を出せないので、既存のフォールバックを作る。 */
    fun ensureCategories(context: Context) {
        if (PromptCardManager.categoryOrder.isEmpty()) {
            PromptCardManager.addCategory(context, FALLBACK_CARD_CATEGORY)
        }
        if (TagManager.categories.isEmpty()) {
            TagManager.addCategory(context, FALLBACK_TAG_CATEGORY)
        }
    }

    suspend fun decide(endpoint: String, key: String, word: String,
                       catalog: ElementCatalog, model: String): Pair<ElementCategory, ElementCategory> {
        val body = JevElementPolicy.decisionBody(word, catalog, model)
        val response = JevElementClient.post(endpoint, key, body)
        val answers = response.optJSONObject("answers")
            ?: throw IllegalStateException("判断APIの応答にanswersがありません。")
        val card = JevElementPolicy.category(
            answers.optJSONObject("card_category")
                ?: throw IllegalStateException("応答にcard_categoryがありません。"),
            catalog.cards)
        val tag = JevElementPolicy.category(
            answers.optJSONObject("tag_category")
                ?: throw IllegalStateException("応答にtag_categoryがありません。"),
            catalog.tags)
        return card to tag
    }

    suspend fun write(endpoint: String, key: String, word: String,
                      writer: String, instruction: String): ElementText {
        val response = JevElementClient.post(endpoint, key, JevElementPolicy.writerBody(word, writer, instruction))
        val content = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?.optString("content")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("生成APIの応答に文章がありません。")
        return try {
            JevElementPolicy.parseText(content)
        } catch (error: JSONException) {
            throw IllegalStateException("生成文章のJSONを読めません。もう一度生成してください。", error)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException(error.message ?: "生成内容が不正です。", error)
        }
    }

    fun existingNames(): Collection<String> =
        PromptCardManager.promptCards.map { it.label } + TagManager.allTags

    /**
     * 新規カード・タグ・紐付けをまとめて登録する。
     * 保存前のSharedPreferences文字列を退避し、途中失敗はそこへ戻す。
     */
    fun persist(context: Context, draft: ElementDraft) {
        val schema = catalog()
        require(schema.cards.isNotEmpty() && schema.tags.isNotEmpty()) { "カテゴリーが未作成です。" }
        JevElementPolicy.validate(draft, schema, existingNames())

        val card = PromptCard(
            id = "jev-" + java.util.UUID.randomUUID().toString(),
            label = draft.name,
            mainPrompt = draft.text.main,
            negativePrompt = draft.text.negative,
            category = draft.cardCategory,
            appliedTags = mutableSetOf(draft.name))
        val category = TagManager.categories.find { it.name == draft.tagCategory }
            ?: throw IllegalStateException("タグのカテゴリーが見つかりません。")

        val cardPrefs = context.getSharedPreferences("prompt_card_prefs", Context.MODE_PRIVATE)
        val tagPrefs = context.getSharedPreferences("tag_prefs", Context.MODE_PRIVATE)
        val oldCards = cardPrefs.getString("prompt_cards", null)
        val oldLevels = cardPrefs.getString("prompt_selection_levels", null)
        val oldCategories = tagPrefs.getString("tag_categories", null)
        val oldVariants = tagPrefs.getString("tag_prompt_variants", null)

        try {
            category.tags.add(draft.name)
            TagManager.tagPromptVariants[draft.name] =
                mutableListOf(TagPromptVariant(TagVariantPolicy.ORIGINAL_NAME, draft.text.chat))
            PromptCardManager.promptCards.add(card)
            TagManager.saveTags(context)
            PromptCardManager.saveCards(context)
        } catch (error: Exception) {
            rollback(context, card.id, draft.name, oldCards, oldLevels, oldCategories, oldVariants)
            throw error
        }
    }

    private fun rollback(context: Context, cardId: String, tag: String,
                         oldCards: String?, oldLevels: String?,
                         oldCategories: String?, oldVariants: String?) {
        runCatching {
            PromptCardManager.promptCards.removeAll { it.id == cardId }
            TagManager.categories.forEach { it.tags.remove(tag) }
            TagManager.tagPromptVariants.remove(tag)
            restore(context.getSharedPreferences("prompt_card_prefs", Context.MODE_PRIVATE),
                "prompt_cards" to oldCards, "prompt_selection_levels" to oldLevels)
            restore(context.getSharedPreferences("tag_prefs", Context.MODE_PRIVATE),
                "tag_categories" to oldCategories, "tag_prompt_variants" to oldVariants)
            TagManager.loadTags(context, forceReload = true)
            PromptCardManager.loadCards(context)
        }
    }

    private fun restore(prefs: android.content.SharedPreferences, vararg entries: Pair<String, String?>) {
        val editor = prefs.edit()
        entries.forEach { (key, value) ->
            if (value == null) { editor.remove(key) } else { editor.putString(key, value) }
        }
        editor.apply()
    }
}
