package com.example.kennys_dokidoki_wallpaper

/**
 * Final guard against common LLM prompt inflation. The LLM is instructed to be minimal,
 * then this removes stock quality/time tags that were not present in the user's wording.
 */
object PromptCardPromptMinimalizer {
    private data class ConditionalTag(val names: Set<String>, val sourceHints: Set<String>)

    private val conditionalTags = listOf(
        ConditionalTag(setOf("8k", "8k resolution"), setOf("8k")),
        ConditionalTag(setOf("4k", "4k resolution"), setOf("4k")),
        ConditionalTag(setOf("uhd", "ultra hd"), setOf("uhd", "ultra hd")),
        ConditionalTag(setOf("hdr"), setOf("hdr")),
        ConditionalTag(setOf("masterpiece"), setOf("masterpiece", "傑作")),
        ConditionalTag(setOf("best quality"), setOf("best quality", "最高品質")),
        ConditionalTag(setOf("high quality"), setOf("high quality", "高品質")),
        ConditionalTag(
            setOf("ultra detailed", "highly detailed", "intricate details", "detailed"),
            setOf("detailed", "details", "詳細", "精細", "細かく")
        ),
        ConditionalTag(
            setOf("photorealistic", "realistic", "hyperrealistic"),
            setOf("photorealistic", "realistic", "写実", "写真のよう", "リアル")
        ),
        ConditionalTag(setOf("morning"), setOf("morning", "朝", "早朝")),
        ConditionalTag(setOf("sunrise"), setOf("sunrise", "日の出", "朝焼け")),
        ConditionalTag(setOf("sunset"), setOf("sunset", "夕焼け", "夕日", "日没")),
        ConditionalTag(setOf("night"), setOf("night", "夜", "夜中")),
        ConditionalTag(setOf("golden hour"), setOf("golden hour", "ゴールデンアワー")),
        ConditionalTag(setOf("cinematic", "cinematic lighting"), setOf("cinematic", "映画的")),
        ConditionalTag(setOf("sharp focus"), setOf("sharp focus", "ピント", "くっきり")),
        ConditionalTag(setOf("beautiful", "stunning"), setOf("beautiful", "stunning", "美しい")),
        ConditionalTag(setOf("professional", "professional photography"), setOf("professional", "プロ")),
        ConditionalTag(setOf("award winning", "trending on artstation"), setOf("award winning", "artstation", "受賞")),
        ConditionalTag(setOf("dramatic lighting", "volumetric lighting"), setOf("dramatic lighting", "volumetric lighting", "劇的な光", "ボリュームライト")),
        ConditionalTag(setOf("depth of field", "bokeh"), setOf("depth of field", "bokeh", "被写界深度", "ボケ")),
        ConditionalTag(setOf("field", "meadow", "pasture"), setOf("field", "meadow", "pasture", "草原", "野原", "牧場")),
        ConditionalTag(setOf("sunlight", "morning light"), setOf("sunlight", "morning light", "日光", "朝日")),
        ConditionalTag(setOf("blue sky", "clouds"), setOf("blue sky", "clouds", "青空", "雲")),
        ConditionalTag(setOf("outdoors"), setOf("outdoors", "屋外", "外で"))
    )

    fun minimize(
        result: PromptCardAiResult,
        naturalLanguage: String,
        existingNegative: String,
        useExisting: Boolean
    ): PromptCardAiResult {
        val source = naturalLanguage.lowercase()
        val seen = mutableSetOf<String>()
        val main = result.mainPrompt.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { tag ->
                val normalized = normalize(tag)
                val rule = conditionalTags.firstOrNull { rule ->
                    rule.names.any { name -> containsPhrase(normalized, name) }
                }
                rule == null || rule.sourceHints.any { hint -> source.contains(hint) }
            }
            .filter { seen.add(normalize(it)) }
            .joinToString(", ")

        val hasNegativeRequest = listOf(
            "without", "exclude", "avoid", "no ", "not ",
            "なし", "除外", "避け", "入れない", "描かない", "不要"
        ).any { source.contains(it) }
        val negative = if (useExisting && existingNegative.isNotBlank()) {
            result.negativePrompt
        } else if (hasNegativeRequest) {
            result.negativePrompt
        } else {
            ""
        }
        return PromptCardAiResult(mainPrompt = main, negativePrompt = negative)
    }

    private fun containsPhrase(value: String, phrase: String): Boolean =
        value == phrase || value.startsWith("$phrase ") || value.endsWith(" $phrase") ||
            value.contains(" $phrase ")

    private fun normalize(tag: String): String = tag.lowercase().trim()
        .removePrefix("(").removeSuffix(")")
        .substringBefore(':').trim()
}
