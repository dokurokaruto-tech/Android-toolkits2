package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/*
 * 指示書を並べる枠（ユーザーが自由に増やせる）と、指示書そのものの共有一覧。
 *
 *   [カテゴリー]  枠の名前と順番。結合順を決める
 *   [一覧]        枠に結ばれた文。全ペルソナが同じものを使う
 *                        ↓ id で引く
 *   UserPersona.items = (共通の行 id + このペルソナの on/off) だけ
 *
 * どれか1つのペルソナで文・枠・並びを変えたら、全ペルソナにそのまま返る。
 * ペルソナごとに違うのは on/off だけ。
 */
private const val PREFS = "user_persona_prefs"

private fun preferences(context: Context): SharedPreferences =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

/** 設定が壊れていても画面は開ける。空として扱う。 */
private fun parseArray(raw: String?): JSONArray? = try {
    raw?.let { JSONArray(it) }
} catch (error: JSONException) {
    null
}

/** 並び枠。id は固定なので、名前を変えても既存の指示はついてくる。 */
data class PersonaCategory(val id: String = UUID.randomUUID().toString(), var name: String)

/** プール候補。categoryId が「この文はどの枠に入るか」の共通の束縛そのもの。 */
data class PersonaPoolEntry(
    val id: String = UUID.randomUUID().toString(),
    var body: String,
    var categoryId: String = PersonaGroupPolicy.UNGROUPED_ID
)

/** カテゴリーの一覧と順番。ペルソナ共通で使う。 */
object PersonaCategories {
    private const val KEY = "persona_categories"

    /** 何も無いときの初期値。消しても自分で増やせる。 */
    private val DEFAULT_NAMES = listOf("社会的な立場", "容姿・年齢", "文章の基本構成", "その他")

    private val items = mutableListOf<PersonaCategory>()

    fun all(): List<PersonaCategory> = items.toList()

    fun nameOf(id: String): String =
        items.firstOrNull { it.id == id }?.name ?: PersonaGroupPolicy.UNGROUPED_LABEL

    fun load(context: Context) {
        items.clear()
        read(context).mapTo(items) { PersonaCategory(id = it.first, name = it.second) }
        if (items.isEmpty()) {
            seedDefaults(context)
        }
    }

    /** 空名と同名は弾く。追加できたかどうかだけ返す。 */
    fun add(context: Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || items.any { it.name == clean }) {
            return false
        }
        items.add(PersonaCategory(name = clean))
        save(context)
        return true
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || items.any { it.id != id && it.name == clean }) {
            return false
        }
        val target = items.firstOrNull { it.id == id } ?: return false
        target.name = clean
        save(context)
        return true
    }

    /** ↑↓ ボタン用の移動。範囲外は何もしない。 */
    fun move(context: Context, from: Int, to: Int) {
        if (!swap(from, to)) {
            return
        }
        save(context)
    }

    /** 枠を消す。入っていた指示は未分類へ落ちて、文自体は残る。 */
    fun remove(context: Context, id: String) {
        if (items.removeAll { it.id == id }) {
            save(context)
        }
    }

    private fun seedDefaults(context: Context) {
        DEFAULT_NAMES.forEach { items.add(PersonaCategory(name = it)) }
        save(context)
    }

    private fun swap(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) {
            return false
        }
        items.add(to, items.removeAt(from))
        return true
    }

    private fun read(context: Context): List<Pair<String, String>> {
        val array = parseArray(preferences(context).getString(KEY, null)) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            obj.optString("id").ifEmpty { UUID.randomUUID().toString() } to name
        }
    }

    private fun save(context: Context) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        preferences(context).edit().putString(KEY, array.toString()).apply()
    }
}

/** 全ペルソナで共有する指示書の一覧。文・枠・並びをここで持つ。 */
object PersonaPool {
    private const val KEY = "persona_pool"

    private val items = mutableListOf<PersonaPoolEntry>()

    fun all(): List<PersonaPoolEntry> = items.toList()

    fun load(context: Context) {
        items.clear()
        val array = parseArray(preferences(context).getString(KEY, null)) ?: return
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val body = obj.optString("body").trim()
            if (body.isEmpty()) {
                continue
            }
            items.add(
                PersonaPoolEntry(
                    id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    body = body,
                    categoryId = obj.optString("categoryId")
                )
            )
        }
    }

    /** id で引く。ペルソナが持つのはこの id と on/off だけ。 */
    fun byId(id: String): PersonaPoolEntry? = items.firstOrNull { it.id == id }

    /** その枠に結ばれている文の数。ペルソナをまたいだ共通の数。 */
    fun countOf(categoryId: String): Int = items.count { it.categoryId == categoryId }

    /** 組み直し用の作業列。entry は中身を共有するので、直せば登録に返る。 */
    fun working(): MutableList<PersonaPoolEntry> = items

    /**
     * 並びと枠をまとめて書き換える。ドラッグを離した時の1回だけの保存に使う。
     * 並びに載っていない行は末尾に残すので、行が増えた直後でも壊れない。
     */
    fun arrange(context: Context, order: List<Pair<String, String>>) {
        val next = order.mapNotNull { (id, categoryId) -> byId(id)?.apply { this.categoryId = categoryId } }
        items.filterNot { entry -> order.any { it.first == entry.id } }.forEach { next += it }
        replace(context, next)
    }

    /** 枠の順番に一覧全体を並び替える。枠の中の順はそのまま残す。 */
    fun applyCategoryOrder(context: Context, categories: List<PersonaCategory>) {
        val rank = categories.withIndex().associate { (index, category) -> category.id to index }
        val last = categories.size
        val next = items
            .mapIndexed { index, entry -> Triple(rank[entry.categoryId] ?: last, index, entry) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
        replace(context, next)
    }

    /** 一覧そのものを置き換える。中身が変わっていなければ保存もしない。 */
    fun replace(context: Context, next: List<PersonaPoolEntry>) {
        val target = next.toList()
        if (target == items) {
            return
        }
        items.clear()
        items.addAll(target)
        save(context)
    }

    /** 空と重複は弾いて追加。枠が決まっていれば、それごと預かる。 */
    fun add(context: Context, body: String, categoryId: String = PersonaGroupPolicy.UNGROUPED_ID): PersonaPoolEntry? {
        val clean = body.trim()
        if (clean.isEmpty() || items.any { it.body.trim() == clean }) {
            return null
        }
        return PersonaPoolEntry(body = clean, categoryId = categoryId).also {
            items.add(it)
            save(context)
        }
    }

    fun update(context: Context, id: String, body: String): Boolean {
        val clean = body.trim()
        if (clean.isEmpty() || items.any { it.id != id && it.body.trim() == clean }) {
            return false
        }
        val target = items.firstOrNull { it.id == id } ?: return false
        target.body = clean
        save(context)
        return true
    }

    fun move(context: Context, from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) {
            return
        }
        items.add(to, items.removeAt(from))
        save(context)
    }

    /** 行を消すと、次の同期で全ペルソナの並びから外れる。 */
    fun remove(context: Context, id: String) {
        if (items.removeAll { it.id == id }) {
            save(context)
        }
    }

    private fun save(context: Context) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("body", it.body).put("categoryId", it.categoryId)) }
        preferences(context).edit().putString(KEY, array.toString()).apply()
    }
}
