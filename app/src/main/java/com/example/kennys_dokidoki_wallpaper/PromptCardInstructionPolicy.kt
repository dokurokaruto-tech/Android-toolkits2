package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * プロンプトカードをAIで書くときの指示書。
 * タグ側のプリセットとは別の場所に置く。
 */
object PromptCardInstructionPolicy {
    const val PROFILES_KEY = "prompt_card_ai_system_prompt_profiles"
    const val LEGACY_KEY = "prompt_card_ai_system_prompt"
    const val DEFAULT_PROFILE_NAME = "メイン"

    val DEFAULT_PROMPT = """
        You convert Japanese or English natural-language image descriptions into MINIMAL Stable Diffusion prompts.
        Return exactly one JSON object and no markdown:
        {"main_prompt":"comma-separated English visual tags","negative_prompt":"comma-separated English negative tags"}

        ABSOLUTE RULE: output only the smallest set of essential visual elements explicitly stated by the user.
        Translate stated nouns, attributes, actions, and relationships. Do not enrich, beautify, or complete the scene.
        Never infer time of day, weather, location, background, lighting, camera, composition, art style, mood, or colors unless explicitly stated.
        Never add quality boilerplate such as masterpiece, best quality, high quality, detailed, 8k, 4k, HDR, sharp focus, cinematic, or photorealistic unless that exact idea was explicitly requested.
        Keep negative_prompt empty unless the user explicitly says to exclude or avoid something, or asks to preserve an existing negative prompt.
        Preserve existing LoRA tokens or weighted syntax only when an existing prompt is supplied.
        Bind every adjective, size, color, and intensity directly to the noun it describes in the SAME comma-separated phrase.
        Never output a free-floating modifier such as "very huge, horse" or "red, horse" because it can affect every subject.

        Minimal examples:
        User: 馬
        Output: {"main_prompt":"horse","negative_prompt":""}
        User: 大きい馬
        Output: {"main_prompt":"very huge horse","negative_prompt":""}
        User: 赤い馬が走っている
        Output: {"main_prompt":"red horse, running","negative_prompt":""}
        Do not turn 大きい馬 into "very huge, horse".
        Do not turn 馬 into "horse, morning, field, sunlight, masterpiece, 8k".
        Never add commentary, explanations, or JSON fields other than main_prompt and negative_prompt.
    """.trimIndent()

    data class Profile(val name: String, val content: String)

    fun parse(profilesJson: String?, legacyPrompt: String?): List<Profile> {
        if (!profilesJson.isNullOrBlank()) {
            return runCatching {
                val array = JSONArray(profilesJson)
                buildList {
                    for (index in 0 until array.length()) {
                        val obj = array.optJSONObject(index) ?: continue
                        val name = obj.optString("name").trim()
                        val content = obj.optString("content")
                        if (name.isNotEmpty()) add(Profile(name, content))
                    }
                }
            }.getOrDefault(emptyList())
        }
        val legacy = legacyPrompt?.trim().orEmpty()
        return listOf(Profile(DEFAULT_PROFILE_NAME, legacy.ifEmpty { DEFAULT_PROMPT }))
    }

    fun encode(profiles: List<Profile>): String {
        val array = JSONArray()
        ensureNonEmpty(profiles).forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("name", profile.name)
                    put("content", profile.content)
                }
            )
        }
        return array.toString()
    }

    fun ensureNonEmpty(profiles: List<Profile>): List<Profile> {
        val cleaned = profiles.map { profile ->
            Profile(
                name = profile.name.trim().ifEmpty { DEFAULT_PROFILE_NAME },
                content = profile.content
            )
        }.filter { it.name.isNotEmpty() }
        return cleaned.ifEmpty { listOf(Profile(DEFAULT_PROFILE_NAME, DEFAULT_PROMPT)) }
    }

    fun canDelete(profiles: List<Profile>): Boolean = profiles.size > 1

    fun removeAt(profiles: List<Profile>, index: Int): List<Profile> {
        if (!canDelete(profiles) || index !in profiles.indices) return ensureNonEmpty(profiles)
        return ensureNonEmpty(profiles.filterIndexed { i, _ -> i != index })
    }

    fun selectedIndex(profiles: List<Profile>, selectedName: String?): Int {
        if (profiles.isEmpty()) return 0
        val match = profiles.indexOfFirst { it.name == selectedName }
        return if (match >= 0) match else 0
    }

    fun usesIndependentKeysFromTags(): Boolean {
        return PROFILES_KEY != TagInstructionPolicy.PROFILES_KEY &&
            LEGACY_KEY != TagInstructionPolicy.LEGACY_KEY
    }
}
