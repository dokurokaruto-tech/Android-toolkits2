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
 *   [プール]      全カテゴリー共通の候補。登録時に本文をコピーする
 *                        ↓
 *   UserPersona.items = (プール由来の本文 + categoryId + 有効フラグ) の平坦な列 = 結合順
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

/** プール候補。 */
data class PersonaPoolEntry(val id: String = UUID.randomUUID().toString(), var body: String)

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

/** 候補文のプール。カテゴリーでは絞らない。 */
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
            items.add(PersonaPoolEntry(id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() }, body = body))
        }
    }

    fun bodyOf(id: String): String = items.firstOrNull { it.id == id }?.body.orEmpty()

    /** 空と重複は弾いて追加。 */
    fun add(context: Context, body: String): PersonaPoolEntry? {
        val clean = body.trim()
        if (clean.isEmpty() || items.any { it.body.trim() == clean }) {
            return null
        }
        return PersonaPoolEntry(body = clean).also {
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
     * 今ある指示書をプールへ投げる。同じ文は増やさない。
     * UserPersonaManager.loadPersonas から呼ぶので、旧データは開いた瞬間に揃う。
     */
    fun absorb(context: Context, bodies: List<String>) {
        val seen = items.mapTo(mutableSetOf()) { it.body.trim() }
        val before = items.size
        bodies.forEach { raw ->
            val body = raw.trim()
            if (body.isEmpty() || !seen.add(body)) {
                return@forEach
            }
            items.add(PersonaPoolEntry(body = body))
        }
        if (items.size > before) {
            save(context)
        }
    }

    private fun save(context: Context) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("body", it.body)) }
        preferences(context).edit().putString(KEY, array.toString()).apply()
    }
}
