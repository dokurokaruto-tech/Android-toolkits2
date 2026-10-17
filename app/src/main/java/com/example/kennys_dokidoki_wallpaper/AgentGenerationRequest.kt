package com.example.kennys_dokidoki_wallpaper

/**
 * One image request persisted and executed by the PC generation agent.
 * seed=null は通常生成と同じく PC 側が毎回ランダムに選ぶ。
 */
data class AgentGenerationRequest(
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val samplerName: String,
    val purpose: String = "image",
    val seed: Long? = null,
    val tags: List<String> = emptyList(),
    val cardStates: Map<String, Int> = emptyMap(),
    val randomPickedIds: Set<String> = emptySet(),
    val randomEnabledCategories: Set<String> = emptySet()
)
