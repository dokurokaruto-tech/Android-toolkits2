package com.example.kennys_dokidoki_wallpaper

import java.util.UUID

/**
 * ペルソナごとに持っていた指示書を、共通の一覧へ寄せる。
 * 寄せた後は on/off だけが行として残り、文・枠・並びは全ペルソナで同じになる。
 *
 *   persona.items(旧: 文 + 枠 + on/off)
 *        │ 同じ文は同じ行に揃え、id を共通の行へ合わせ直す
 *        v
 *   [共通の一覧] (id + 文 + 枠) ──この順と on/off で──> persona.items(id + 文 + on/off)
 */
object PersonaLineAligner {

    /** 書き換えが起きたかどうか。保存は呼び手がまとめる。 */
    data class Result(val poolChanged: Boolean, val personasChanged: Boolean)

    /**
     * @param legacyCategory 旧形式でペルソナ側が残していた「行のid → 枠」。拾えた分だけ預かる。
     * @param defaultOn どのペルソナにも無かった行を点けるかどうか。
     *                  初回の寄せ込みは今組んでいる文を変えないよう false、それ以降は true。
     */
    fun align(
        pool: MutableList<PersonaPoolEntry>,
        personas: List<UserPersona>,
        legacyCategory: Map<String, String> = emptyMap(),
        defaultOn: Boolean = true
    ): Result {
        val taken = pool.mapTo(mutableSetOf()) { it.id }
        val wanted = personas.map { mutableMapOf<String, Boolean>() }
        var poolChanged = false

        personas.forEachIndexed { index, persona ->
            persona.items.forEach { item ->
                val body = item.content.trim()
                if (body.isEmpty()) {
                    return@forEach
                }
                val entry = pool.firstOrNull { it.body.trim() == body }
                    ?: newLine(item, body, taken, legacyCategory).also {
                        pool.add(it)
                        taken += it.id
                        poolChanged = true
                    }
                val flags = wanted[index]
                flags[entry.id] = (flags[entry.id] ?: false) || item.isEnabled
            }
        }

        var personasChanged = false
        personas.forEachIndexed { index, persona ->
            val flags = wanted[index]
            val next = pool.map { entry ->
                PersonaItem(id = entry.id, content = entry.body, isEnabled = flags[entry.id] ?: defaultOn)
            }
            if (next != persona.items) {
                persona.items = next.toMutableList()
                personasChanged = true
            }
        }
        return Result(poolChanged, personasChanged)
    }

    /**
     * どの行にも当てはまらなかった文を、新しい共通の行にする。
     * id が空いていればそのまま引き継ぎ、埋まっていたら新しいidで打ち直す。
     */
    private fun newLine(
        item: PersonaItem,
        body: String,
        taken: Set<String>,
        legacyCategory: Map<String, String>
    ): PersonaPoolEntry {
        val reusable = item.id !in taken
        return PersonaPoolEntry(
            id = if (reusable) item.id else UUID.randomUUID().toString(),
            body = body,
            categoryId = if (reusable) legacyCategory[item.id].orEmpty() else PersonaGroupPolicy.UNGROUPED_ID
        )
    }
}
