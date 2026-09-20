package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 履歴1件の状態。未適用→適用済み→取消済み→適用済み…と行き来する */
internal enum class HistoryStatus {
    PENDING,
    APPLIED,
    REVERTED;
}

/** ビフォーアフターの1行 */
internal data class HistoryRow(val label: String, val before: String, val after: String)

/**
 * 提案・実行の履歴1件。
 * payloadは適用/取消の実行に必要なJSON(文字列化して保持)。
 */
internal data class HistoryRecord(
    val id: String,
    val time: Long,
    val tool: ConciergeTool,
    val title: String,
    val detail: String,
    val status: HistoryStatus,
    val reversible: Boolean,
    val rows: List<HistoryRow>,
    val payload: String
)

/** 履歴の保存と読み出し。SharedPreferencesに新しい順で最大100件 */
internal object HistoryStore {
    private const val PREFS = "concierge_history_prefs"
    private const val KEY = "records_v1"
    const val MAX = 100

    fun newId(): String = UUID.randomUUID().toString()

    fun load(context: Context): MutableList<HistoryRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            val result = mutableListOf<HistoryRecord>()
            for (i in 0 until array.length()) {
                fromJson(array.optJSONObject(i) ?: continue)?.let { result += it }
            }
            result
        }.getOrDefault(mutableListOf())
    }

    fun add(context: Context, record: HistoryRecord) {
        val all = load(context).filter { it.id != record.id }.toMutableList()
        all.add(0, record)
        save(context, all.take(MAX))
    }

    fun update(context: Context, record: HistoryRecord) {
        val all = load(context)
        val index = all.indexOfFirst { it.id == record.id }
        if (index < 0) {
            return
        }
        all[index] = record
        save(context, all)
    }

    fun get(context: Context, id: String): HistoryRecord? =
        load(context).firstOrNull { it.id == id }

    private fun save(context: Context, list: List<HistoryRecord>) {
        val array = JSONArray()
        list.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(record: HistoryRecord): JSONObject {
        val rows = JSONArray()
        record.rows.forEach { rows.put(JSONObject().put("l", it.label).put("b", it.before).put("a", it.after)) }
        return JSONObject()
            .put("id", record.id)
            .put("time", record.time)
            .put("tool", record.tool.id)
            .put("title", record.title)
            .put("detail", record.detail)
            .put("status", record.status.name)
            .put("reversible", record.reversible)
            .put("rows", rows)
            .put("payload", record.payload)
    }

    private fun fromJson(json: JSONObject): HistoryRecord? = runCatching {
        val tool = ConciergeTool.entries.first { it.id == json.getString("tool") }
        val status = HistoryStatus.valueOf(json.getString("status"))
        val rows = mutableListOf<HistoryRow>()
        val array = json.getJSONArray("rows")
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            rows += HistoryRow(row.getString("l"), row.getString("b"), row.getString("a"))
        }
        HistoryRecord(
            id = json.getString("id"),
            time = json.getLong("time"),
            tool = tool,
            title = json.getString("title"),
            detail = json.getString("detail"),
            status = status,
            reversible = json.getBoolean("reversible"),
            rows = rows,
            payload = json.getString("payload")
        )
    }.getOrNull()
}

/** 履歴payloadの生成。HistoryExecuteの読み取りと対にする */
internal object HistoryPayload {
    fun card(target: JevGenieCardRef, newMain: String, newNeg: String): String = JSONObject()
        .put("cardId", target.id).put("label", target.label)
        .put("oldMain", target.mainPrompt).put("oldNeg", target.negativePrompt)
        .put("newMain", newMain).put("newNeg", newNeg).toString()

    fun tag(target: JevGenieTagRef, newText: String): String = JSONObject()
        .put("name", target.name).put("oldText", target.text).put("newText", newText).toString()

    fun element(draft: ElementDraft): String = JSONObject()
        .put("name", draft.name).put("cardCategory", draft.cardCategory).put("tagCategory", draft.tagCategory)
        .put("main", draft.text.main).put("negative", draft.text.negative).put("chat", draft.text.chat)
        .toString()

    fun preset(presetId: String, prev: ConciergeBuilderState): String = JSONObject()
        .put("presetId", presetId).put("prev", builderJson(prev)).toString()

    fun select(ids: List<String>, mode: CardSelectionMode, prev: Map<String, Int>): String = JSONObject()
        .put("ids", JSONArray(ids)).put("mode", mode.name).put("prev", levelsJson(prev)).toString()

    fun filter(tag: String, prev: ConciergeFilterState): String = JSONObject()
        .put("tag", tag)
        .put("prev", JSONObject().put("target", prev.target ?: JSONObject.NULL).put("has", prev.has))
        .toString()

    fun empty(): String = JSONObject().toString()

    private fun builderJson(state: ConciergeBuilderState): JSONObject = JSONObject()
        .put("sel", levelsJson(state.selection)).put("rnd", JSONArray(state.random.toList()))
        .put("w", state.width).put("h", state.height).put("steps", state.steps)
        .put("batch", state.batch).put("sampler", state.sampler)

    private fun levelsJson(levels: Map<String, Int>): JSONObject {
        val json = JSONObject()
        levels.forEach { (id, level) -> json.put(id, level) }
        return json
    }
}

/**
 * 履歴の適用(前方向)と取消(逆方向)の実行。
 * すべてメインスレッドで呼ぶこと。成否を返す。
 */
internal object HistoryExecute {
    fun apply(context: Context, host: ConciergeHost, record: HistoryRecord): Boolean =
        runCatching {
            val payload = JSONObject(record.payload)
            val done = when (record.tool) {
                ConciergeTool.EDIT_CARD -> {
                    val live = liveCard(payload.getString("cardId"), payload.getString("label"))
                        ?: return false
                    live.mainPrompt = payload.getString("newMain")
                    live.negativePrompt = payload.getString("newNeg")
                    PromptCardManager.saveCards(context)
                    host.refreshBuilder()
                    true
                }
                ConciergeTool.EDIT_TAG -> {
                    val name = liveTag(payload.getString("name")) ?: return false
                    TagManager.setTagPrompt(context, name, payload.getString("newText"))
                    host.refreshTags()
                    true
                }
                ConciergeTool.NEW_ELEMENT -> {
                    JevElementService.persist(context, elementOf(payload))
                    host.refreshBuilder()
                    host.refreshTags()
                    true
                }
                ConciergeTool.APPLY_PRESET -> {
                    val preset = PresetManager.presets.firstOrNull { it.id == payload.getString("presetId") }
                        ?: return false
                    host.applyPreset(preset)
                    true
                }
                ConciergeTool.SELECT_CARDS -> {
                    host.selectCards(idsOf(payload.getJSONArray("ids")), modeOf(payload.getString("mode")))
                    true
                }
                ConciergeTool.FILTER_IMAGES -> {
                    host.filterImages(payload.getString("tag"))
                    true
                }
                ConciergeTool.START_GENERATION, ConciergeTool.THUMBNAILS, ConciergeTool.INVESTIGATE, ConciergeTool.TALK -> false
            }
            done
        }.getOrDefault(false)

    fun revert(context: Context, host: ConciergeHost, record: HistoryRecord): Boolean {
        if (!record.reversible) {
            return false
        }
        return runCatching {
            val payload = JSONObject(record.payload)
            val done = when (record.tool) {
                ConciergeTool.EDIT_CARD -> {
                    val live = liveCard(payload.getString("cardId"), payload.getString("label"))
                        ?: return false
                    live.mainPrompt = payload.getString("oldMain")
                    live.negativePrompt = payload.getString("oldNeg")
                    PromptCardManager.saveCards(context)
                    host.refreshBuilder()
                    true
                }
                ConciergeTool.EDIT_TAG -> {
                    val name = liveTag(payload.getString("name")) ?: return false
                    TagManager.setTagPrompt(context, name, payload.getString("oldText"))
                    host.refreshTags()
                    true
                }
                // 作ったものを消す。既に手で消し済みなら消えた状態で成功扱い
                ConciergeTool.NEW_ELEMENT -> {
                    val name = payload.getString("name")
                    PromptCardManager.promptCards
                        .firstOrNull { JevElementPolicy.sameName(it.label, name) }
                        ?.let { PromptCardManager.deleteCard(context, it) }
                    liveTag(name)?.let { TagManager.deleteTag(context, it) }
                    host.refreshBuilder()
                    host.refreshTags()
                    true
                }
                ConciergeTool.APPLY_PRESET ->
                    host.restoreBuilder(builderOf(payload.getJSONObject("prev"))).let { true }
                ConciergeTool.SELECT_CARDS ->
                    host.restoreSelection(levelsOf(payload.getJSONObject("prev"))).let { true }
                ConciergeTool.FILTER_IMAGES -> {
                    val prev = payload.getJSONObject("prev")
                    host.setFilter(
                        if (prev.isNull("target")) null else prev.getString("target"),
                        prev.getBoolean("has")
                    )
                    true
                }
                ConciergeTool.START_GENERATION, ConciergeTool.THUMBNAILS, ConciergeTool.INVESTIGATE, ConciergeTool.TALK -> false
            }
            done
        }.getOrDefault(false)
    }

    private fun liveCard(id: String, label: String): PromptCard? =
        PromptCardManager.promptCards.firstOrNull { it.id == id }
            ?: PromptCardManager.promptCards.firstOrNull { JevElementPolicy.sameName(it.label, label) }

    private fun liveTag(name: String): String? =
        TagManager.allTags.firstOrNull { JevElementPolicy.sameName(it, name) }

    private fun elementOf(payload: JSONObject): ElementDraft = ElementDraft(
        name = payload.getString("name"),
        cardCategory = payload.getString("cardCategory"),
        tagCategory = payload.getString("tagCategory"),
        text = ElementText(
            payload.getString("main"),
            payload.getString("negative"),
            payload.getString("chat")
        )
    )

    private fun idsOf(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    private fun modeOf(raw: String): CardSelectionMode =
        if (raw == CardSelectionMode.REPLACE.name) CardSelectionMode.REPLACE else CardSelectionMode.ADD

    private fun builderOf(json: JSONObject): ConciergeBuilderState {
        val selection = levelsOf(json.getJSONObject("sel"))
        val random = mutableSetOf<String>()
        val array = json.getJSONArray("rnd")
        for (i in 0 until array.length()) {
            random += array.getString(i)
        }
        return ConciergeBuilderState(
            selection = selection,
            random = random,
            width = json.getInt("w"),
            height = json.getInt("h"),
            steps = json.getInt("steps"),
            batch = json.getInt("batch"),
            sampler = json.getString("sampler")
        )
    }

    private fun levelsOf(json: JSONObject): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        json.keys().forEach { result[it] = json.getInt(it) }
        return result
    }
}
