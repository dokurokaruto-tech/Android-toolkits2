package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ユーザーの人格（ペルソナ）内の個別指示。
 * Geminiのカスタム指示のように、個別に有効/無効、並べ替えができるわ☆
 */
data class PersonaItem(
    val id: String = UUID.randomUUID().toString(),
    var content: String,
    var isEnabled: Boolean = true
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("content", content)
            put("isEnabled", isEnabled)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PersonaItem {
            return PersonaItem(
                id = json.optString("id", UUID.randomUUID().toString()),
                content = json.getString("content"),
                isEnabled = json.optBoolean("isEnabled", true)
            )
        }
    }
}

/**
 * ユーザーの「人格（ペルソナ）」を複数管理するデータクラス。
 * 複数のPersonaItemを組み合わせてひとつのプロンプトを作るのよ♪
 */
data class UserPersona(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var description: String = "",
    var items: MutableList<PersonaItem> = mutableListOf()
) {
    /**
     * 有効な指示を上から順番に結合してひとつのプロンプトにするわよ！
     * 互換性のため、もしitemsが空でdescriptionが残っている場合はそれを返すわ。
     */
    val mergedPrompt: String
        get() {
            if (items.isEmpty() && description.isNotEmpty()) {
                return description
            }
            return items.filter { it.isEnabled }.joinToString("\n") { it.content }.trim()
        }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            put("items", array)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): UserPersona {
            val itemsList = mutableListOf<PersonaItem>()
            if (json.has("items")) {
                val array = json.getJSONArray("items")
                for (i in 0 until array.length()) {
                    try {
                        itemsList.add(PersonaItem.fromJson(array.getJSONObject(i)))
                    } catch (e: Exception) {
                        // 壊れたアイテムはスキップ
                    }
                }
            }
            val desc = json.optString("description", "")
            // itemsが空でdescriptionがある場合は移行
            if (itemsList.isEmpty() && desc.isNotEmpty()) {
                itemsList.add(PersonaItem(content = desc, isEnabled = true))
            }
            return UserPersona(
                id = json.getString("id"),
                name = json.getString("name"),
                description = desc,
                items = itemsList
            )
        }
    }
}

object UserPersonaManager {
    private const val PREFS_NAME = "user_persona_prefs"
    private const val KEY_PERSONAS = "personas_list"
    private const val KEY_ACTIVE_ID = "active_persona_id"

    val personas = mutableListOf<UserPersona>()
    var activePersonaId: String? = null
        private set

    val activePersona: UserPersona?
        get() = personas.find { it.id == activePersonaId }

    fun loadPersonas(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PERSONAS, null)
        personas.clear()

        if (json != null) {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                try {
                    personas.add(UserPersona.fromJson(array.getJSONObject(i)))
                } catch (e: Exception) {
                    // 壊れたデータはスキップ
                }
            }
        }

        activePersonaId = prefs.getString(KEY_ACTIVE_ID, null)

        // アクティブIDが存在しないペルソナを指している場合はクリア
        if (activePersonaId != null && personas.none { it.id == activePersonaId }) {
            activePersonaId = null
            savePersonas(context)
        }
    }

    fun savePersonas(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        personas.forEach { array.put(it.toJson()) }

        prefs.edit()
            .putString(KEY_PERSONAS, array.toString())
            .putString(KEY_ACTIVE_ID, activePersonaId)
            .apply()
    }

    fun addPersona(context: Context, name: String, description: String, items: List<PersonaItem> = emptyList()): UserPersona {
        val persona = UserPersona(
            name = name,
            description = description,
            items = items.toMutableList()
        )
        // itemsが空でdescriptionが非空なら自動変換
        if (persona.items.isEmpty() && description.isNotEmpty()) {
            persona.items.add(PersonaItem(content = description, isEnabled = true))
        }
        personas.add(persona)
        // 最初のペルソナなら自動でアクティブにする
        if (personas.size == 1) {
            activePersonaId = persona.id
        }
        savePersonas(context)
        return persona
    }

    fun editPersona(context: Context, id: String, newName: String, newDescription: String, newItems: List<PersonaItem> = emptyList()) {
        val persona = personas.find { it.id == id } ?: return
        persona.name = newName
        persona.description = newDescription
        persona.items = newItems.toMutableList()
        savePersonas(context)
    }

    fun updatePersona(context: Context, updated: UserPersona) {
        val idx = personas.indexOfFirst { it.id == updated.id }
        if (idx != -1) {
            personas[idx] = updated
        } else {
            personas.add(updated)
        }
        savePersonas(context)
    }

    fun removePersona(context: Context, id: String) {
        personas.removeAll { it.id == id }
        if (activePersonaId == id) {
            activePersonaId = personas.firstOrNull()?.id
        }
        savePersonas(context)
    }

    fun setActivePersona(context: Context, id: String?) {
        activePersonaId = if (id != null && personas.any { it.id == id }) id else null
        savePersonas(context)
    }

    /** アクティブペルソナを解除（ペルソナなし状態に戻す） */
    fun clearActivePersona(context: Context) {
        activePersonaId = null
        savePersonas(context)
    }
}
