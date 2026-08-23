package com.example.kennys_dokidoki_wallpaper

/**
 * プリセットのサムネイルは、登録カードに加えてランダムONのカテゴリーからも
 * 1枚ずつ引いてプロンプトを組む。通常生成と同じ抽選経路を使う。
 */
object PresetThumbnailPromptPolicy {
    fun selectedCards(
        preset: Preset,
        roster: List<PromptCard>
    ): List<Pair<PromptCard, Int>> = preset.activePromptStates.mapNotNull { (id, level) ->
        roster.find { it.id == id }?.let { it to level }
    }

    fun prepare(
        preset: Preset,
        roster: List<PromptCard>,
        randomizerIncludedIds: Set<String>,
        chance: () -> Int,
        pickIndex: (Int) -> Int
    ): GeneratedImageTagBinding.PreparedImage? {
        val selected = selectedCards(preset, roster)
        val snapshot = GeneratedImageTagBinding.snapshotAtStart(
            selected = selected,
            roster = roster,
            randomEnabledCategories = preset.randomEnabledCategories,
            randomizerIncludedIds = randomizerIncludedIds,
            width = preset.width,
            height = preset.height,
            steps = preset.steps,
            sampler = preset.sampler,
            batchCount = 1
        )
        return GeneratedImageTagBinding.buildPreparedImages(snapshot, chance, pickIndex).firstOrNull()
    }

    fun request(
        preset: Preset,
        roster: List<PromptCard>,
        randomizerIncludedIds: Set<String>,
        chance: () -> Int,
        pickIndex: (Int) -> Int,
        width: Int = 1080,
        height: Int = 1920
    ): AgentGenerationRequest? {
        val prepared = prepare(preset, roster, randomizerIncludedIds, chance, pickIndex) ?: return null
        return AgentGenerationRequest(
            prompt = prepared.prompt,
            negativePrompt = prepared.negativePrompt,
            width = width,
            height = height,
            steps = preset.steps,
            samplerName = preset.sampler,
            purpose = "thumbnail",
            cardStates = prepared.cardStates,
            randomPickedIds = prepared.randomPickedIds,
            randomEnabledCategories = prepared.randomEnabledCategories
        )
    }
}
