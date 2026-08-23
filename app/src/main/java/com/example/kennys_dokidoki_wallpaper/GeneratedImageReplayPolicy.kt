package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧中の生成画像を、プロンプト／シード／解像度／サンプラーはそのまま、
 * ステップ数だけ差し替えてもう一度流す。
 */
object GeneratedImageReplayPolicy {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 150
    const val MISSING_PROMPT = "この画像のプロンプトが残っていない。"
    const val MISSING_SEED = "この画像のシードが残っていない。新しく生成した画像なら残る。"
    const val BUSY = "いま生成中じゃ。終わってからにしてくれ。"

    data class Recipe(
        val prompt: String,
        val negativePrompt: String,
        val width: Int,
        val height: Int,
        val steps: Int,
        val sampler: String,
        val seed: Long,
        val tags: List<String>,
        val cardStates: Map<String, Int>,
        val randomPickedIds: Set<String>,
        val randomEnabledCategories: Set<String>
    )

    fun clampSteps(raw: Int?): Int = (raw ?: 20).coerceIn(MIN_STEPS, MAX_STEPS)

    fun parseSeed(raw: Any?): Long? {
        val value = when (raw) {
            null -> return null
            is Long -> raw
            is Int -> raw.toLong()
            is Number -> raw.toLong()
            else -> raw.toString().trim().toLongOrNull() ?: return null
        }
        return value.takeIf { it >= 0L }
    }

    fun missingReason(draft: GeneratedImageLifecycle.Draft?): String? {
        val prompt = draft?.prompt?.trim().orEmpty()
        if (prompt.isEmpty()) return MISSING_PROMPT
        if (parseSeed(draft?.seed) == null) return MISSING_SEED
        return null
    }

    fun recipeFrom(draft: GeneratedImageLifecycle.Draft?): Recipe? {
        if (missingReason(draft) != null) return null
        val source = draft ?: return null
        return Recipe(
            prompt = source.prompt!!.trim(),
            negativePrompt = source.negativePrompt.orEmpty(),
            width = source.width?.takeIf { it > 0 } ?: 720,
            height = source.height?.takeIf { it > 0 } ?: 1280,
            steps = clampSteps(source.steps),
            sampler = source.sampler?.takeIf { it.isNotBlank() } ?: "Euler a",
            seed = parseSeed(source.seed)!!,
            tags = source.tags.toList(),
            cardStates = source.cardStates,
            randomPickedIds = source.randomPickedIds,
            randomEnabledCategories = source.randomEnabledCategories
        )
    }

    fun summary(recipe: Recipe): String =
        "${recipe.width}×${recipe.height} / ${recipe.sampler} / Seed ${recipe.seed}"

    fun request(recipe: Recipe, steps: Int): AgentGenerationRequest =
        AgentGenerationRequest(
            prompt = recipe.prompt,
            negativePrompt = recipe.negativePrompt,
            width = recipe.width,
            height = recipe.height,
            steps = clampSteps(steps),
            samplerName = recipe.sampler,
            seed = recipe.seed,
            tags = recipe.tags,
            cardStates = recipe.cardStates,
            randomPickedIds = recipe.randomPickedIds,
            randomEnabledCategories = recipe.randomEnabledCategories
        )
}
