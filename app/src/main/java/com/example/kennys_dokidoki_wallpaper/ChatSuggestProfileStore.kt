package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A named set of tone/example instructions, optionally linked to a user persona. */
internal data class ChatSuggestProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val instructions: String = "",
    val personaId: String? = null
)

/**
 * Stores Suggest profiles while keeping the old single-instruction preference in sync for
 * existing generation and instruction-browser code. The old value is the one-time migration
 * source for installations that predate profiles.
 */
internal object ChatSuggestProfileStore {
    private const val PREFS_NAME = "settings"
    private const val KEY_PROFILES = "chat_suggest_profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "chat_suggest_active_profile_id"
    private const val KEY_LEGACY_INSTRUCTIONS = "chat_suggest_custom_instructions"
    private const val KEY_FIRST_LINE_TARGET_LENGTHS = "chat_first_user_line_target_lengths"
    private const val NO_PERSONA_TARGET_KEY = "__no_persona__"

    fun load(context: Context): List<ChatSuggestProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        val stored = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                        val name = item.optString("name").trim().ifBlank { "プロフィール ${index + 1}" }
                        val instructions = item.optString("instructions", "")
                        val personaId = item.optString("personaId")
                            .takeIf { it.isNotBlank() && it != "null" }
                        add(ChatSuggestProfile(id, name, instructions, personaId))
                    }
                }
            }.getOrDefault(emptyList())
        }

        if (stored.isEmpty()) {
            val migrated = listOf(
                ChatSuggestProfile(
                    name = "デフォルト",
                    instructions = prefs.getString(KEY_LEGACY_INSTRUCTIONS, "") ?: ""
                )
            )
            save(context, migrated, migrated.first().id)
            return migrated
        }

        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        val active = stored.firstOrNull { it.id == activeId } ?: stored.first()
        // Repair an invalid active ID and keep the compatibility preference in step with it.
        if (active.id != activeId || prefs.getString(KEY_LEGACY_INSTRUCTIONS, null) != active.instructions) {
            save(context, stored, active.id)
        }
        return stored
    }

    fun active(context: Context): ChatSuggestProfile {
        val profiles = load(context)
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE_ID, null)
        return profiles.firstOrNull { it.id == id } ?: profiles.first()
    }

    fun activeId(context: Context): String = active(context).id

    fun save(context: Context, profiles: List<ChatSuggestProfile>, activeId: String) {
        val normalized = if (profiles.isEmpty()) {
            listOf(ChatSuggestProfile(name = "デフォルト"))
        } else {
            val seen = mutableSetOf<String>()
            profiles.mapIndexed { index, profile ->
                val id = profile.id.takeIf { it.isNotBlank() && seen.add(it) }
                    ?: UUID.randomUUID().toString().also { seen.add(it) }
                profile.copy(
                    id = id,
                    name = profile.name.trim().ifBlank { "プロフィール ${index + 1}" }
                )
            }
        }
        val selected = normalized.firstOrNull { it.id == activeId } ?: normalized.first()
        val array = JSONArray().apply {
            normalized.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("instructions", profile.instructions)
                    put("personaId", profile.personaId)
                })
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_PROFILE_ID, selected.id)
            .putString(KEY_LEGACY_INSTRUCTIONS, selected.instructions)
            .apply()
    }

    fun activate(context: Context, profileId: String): Boolean {
        val profiles = load(context)
        if (profiles.none { it.id == profileId }) {
            return false
        }
        save(context, profiles, profileId)
        return true
    }

    /** Switch to the profile linked to a newly selected user persona, if one exists. */
    fun activateForPersona(context: Context, personaId: String?): Boolean {
        val profiles = load(context)
        val currentId = activeId(context)
        val current = profiles.firstOrNull { it.id == currentId }
        val matching = if (personaId.isNullOrBlank()) {
            if (current?.personaId == null) {
                current
            } else {
                profiles.firstOrNull { it.personaId == null }
            }
        } else {
            current?.takeIf { it.personaId == personaId }
                ?: profiles.firstOrNull { it.personaId == personaId }
                ?: current?.takeIf { it.personaId == null }
                ?: profiles.firstOrNull { it.personaId == null }
        } ?: return false
        return activate(context, matching.id)
    }

    /** Target length is stored by stable persona ID; null is the shared no-persona fallback. */
    fun loadTargetCharacters(context: Context): Map<String?, Int> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FIRST_LINE_TARGET_LENGTHS, null)
            ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap<String?, Int> {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optInt(key, Int.MIN_VALUE)
                    if (value in ChatFirstUserLinePolicy.MIN_TARGET_CHARACTERS..ChatFirstUserLinePolicy.MAX_TARGET_CHARACTERS) {
                        put(if (key == NO_PERSONA_TARGET_KEY) null else key, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun targetCharacters(context: Context, personaId: String?): Int =
        loadTargetCharacters(context)[personaId] ?: ChatFirstUserLinePolicy.DEFAULT_TARGET_CHARACTERS

    fun saveTargetCharacters(context: Context, targets: Map<String?, Int>) {
        val json = JSONObject()
        targets.forEach { (personaId, value) ->
            if (value in ChatFirstUserLinePolicy.MIN_TARGET_CHARACTERS..ChatFirstUserLinePolicy.MAX_TARGET_CHARACTERS) {
                json.put(personaId ?: NO_PERSONA_TARGET_KEY, value)
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_FIRST_LINE_TARGET_LENGTHS, json.toString())
            .apply()
    }

    /** Keeps edits made in the instruction browser attached to the currently selected profile. */
    fun updateActiveInstructions(context: Context, instructions: String) {
        val profiles = load(context)
        val selected = active(context)
        save(
            context,
            profiles.map { profile ->
                if (profile.id == selected.id) {
                    profile.copy(instructions = instructions)
                } else {
                    profile
                }
            },
            selected.id
        )
    }
}
