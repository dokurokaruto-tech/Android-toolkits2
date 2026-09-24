package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/*
 * 指示書を並べる枠（ユーザーが自由に増やせる）と、枠をどれを選んでも同じ一覧になる候補文のプール。
 *
 *   [カテゴリー]  結合の「順」だけを決める。中身は制限しない
 *   [プール]      全カテゴリー共通の候補。文ごとに枠を1つだけ持つ
 *                        ↓ 本文（前後の空白は無視）で引く
 *   UserPersona.items = プールからコピーした本文 + 有効フラグ の平坦な列 = 結合順
 *
 * 文と枠の結びつきはペルソナ側には持たせない。どれか1つのペルソナで枠を動かせば、
 * 同じ文を使っているペルソナは全部まとめて並び直る。
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

/** 候補文のプール。枠では絞らないが、文ごとの「どの枠か」はここで持つ。 */
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

    /** 本文 → 枠。ペルソナ側はこれで並びを見る。 */
    fun bindings(): Map<String, String> = items.associate { it.body.trim() to it.categoryId }

    fun categoryIdOf(body: String): String = PersonaGroupPolicy.categoryOf(bindings(), body)

    /** その枠に結ばれている文の数。ペルソナをまたいだ共通の数。 */
    fun countOf(categoryId: String): Int = items.count { it.categoryId == categoryId }

    /**
     * 文と枠を結び付ける。プールに無い文なら、その場で候補として加える。
     * 空の枠IDは「未分類」なので、外す使い方もできる。
     */
    fun categorize(context: Context, next: Map<String, String>) {
        var changed = false
        next.forEach { (rawBody, categoryId) ->
            val body = rawBody.trim()
            if (body.isEmpty()) {
                return@forEach
            }
            val target = items.firstOrNull { it.body.trim() == body }
            if (target == null) {
                items.add(PersonaPoolEntry(body = body, categoryId = categoryId))
                changed = true
            } else if (target.categoryId != categoryId) {
                target.categoryId = categoryId
                changed = true
            }
        }
        if (changed) {
            save(context)
        }
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

    /** プールから消しても、登録済みの指示はコピー済みなので無傷。 */
    fun remove(context: Context, id: String) {
        if (items.removeAll { it.id == id }) {
            save(context)
        }
    }

    /**
     * 今ある指示書をプールへ投げる。同じ文は増やさず、枠の指定は空いているときだけ埋める。
     * UserPersonaManager.loadPersonas から呼ぶので、旧データは開いた瞬間に共通の束縛へ移る。
     */
    fun absorb(context: Context, seeds: List<Pair<String, String>>) {
        var changed = false
        seeds.forEach { (rawBody, rawCategory) ->
            val body = rawBody.trim()
            val categoryId = rawCategory.trim()
            if (body.isEmpty()) {
                return@forEach
            }
            val target = items.firstOrNull { it.body.trim() == body }
            if (target == null) {
                items.add(PersonaPoolEntry(body = body, categoryId = categoryId))
                changed = true
            } else if (target.categoryId.isEmpty() && categoryId.isNotEmpty()) {
                target.categoryId = categoryId
                changed = true
            }
        }
        if (changed) {
            save(context)
        }
    }

    private fun save(context: Context) {
        val array = JSONArray()
            array.put(JSONObject().put("id", it.id).put("body", it.body).put("categoryId", it.categoryId))
        preferences(context).edit().putString(KEY, array.toString()).apply()
    }
}
