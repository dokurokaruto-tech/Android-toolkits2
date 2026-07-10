package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * 妄想モード専用の「人格（ペルソナ・指示書）」を管理するマネージャーよ♥
 * UserPersonaManager と全く同じ仕組みで、妄想用に独立して保存するの☆
 */
object DelusionPersonaManager {
    private const val PREFS_NAME = "delusion_persona_prefs"
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
        
        // 初回ロード時に空っぽならデフォルトの妄想指示書をひとつ追加しておくわね☆
        if (personas.isEmpty()) {
            addPersona(
                context, 
                "デフォルトの妄想設定", 
                "", 
                listOf(
                    PersonaItem(content = "より大胆で、少しツンデレな口調になる", isEnabled = true),
                    PersonaItem(content = "二人っきりの秘密の時間を楽しんでいるかのように、照れながら甘えてくる", isEnabled = true)
                )
            )
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
        if (persona.items.isEmpty() && description.isNotEmpty()) {
            persona.items.add(PersonaItem(content = description, isEnabled = true))
        }
        personas.add(persona)
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

    fun clearActivePersona(context: Context) {
        activePersonaId = null
        savePersonas(context)
    }
}
