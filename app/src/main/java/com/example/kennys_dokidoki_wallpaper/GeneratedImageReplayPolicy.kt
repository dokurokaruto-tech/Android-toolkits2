package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧中の生成画像をもう一度流す。プロンプト／解像度／サンプラーはそのまま、
 * ステップ数は差し替え可。シードは残すか、通常生成のように毎回変えるかを選べ、
 * 変える場合はバッチ枚数ぶん（1枚ごとに別シード）を一度に頼める。
 */
object GeneratedImageReplayPolicy {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 150
    const val MISSING_PROMPT = "この画像のプロンプトが残っていない。"
    const val BUSY = "いま生成中じゃ。終わってからにしてくれ。"
    const val RANDOM_SEED_LABEL = "ランダム"

    /** 保存済みシードをそのまま使うか、通常生成と同じく毎回新しくするか。 */
    enum class SeedMode { SAME, NEW }

    data class Recipe(
        val prompt: String,
        val negativePrompt: String,
        val width: Int,
        val height: Int,
        val steps: Int,
        val sampler: String,
        val seed: Long?,
        val tags: List<String>,
        val cardStates: Map<String, Int>,
        val randomPickedIds: Set<String>,
        val randomEnabledCategories: Set<String>
    )

    fun clampSteps(raw: Int?): Int = (raw ?: 20).coerceIn(MIN_STEPS, MAX_STEPS)

    /** バッチ枚数の丸めはビルダーのバッチカウントと同じ規則。 */
    fun clampBatch(raw: Int?): Int =
        BuilderGenSettingPolicy.clamp(BuilderGenSettingPolicy.Axis.BATCH, raw)

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

    /** シードが残っていなくても「変える」側で再生成できるので、必須はプロンプトだけ。 */
    fun missingReason(draft: GeneratedImageLifecycle.Draft?): String? {
        val prompt = draft?.prompt?.trim().orEmpty()
        if (prompt.isEmpty()) return MISSING_PROMPT
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
            seed = parseSeed(source.seed),
            tags = source.tags.toList(),
            cardStates = source.cardStates,
            randomPickedIds = source.randomPickedIds,
            randomEnabledCategories = source.randomEnabledCategories
        )
    }

    fun seedLabel(seed: Long?): String = seed?.toString() ?: RANDOM_SEED_LABEL

    fun summary(recipe: Recipe, seedMode: SeedMode = SeedMode.SAME): String {
        val seed = if (seedMode == SeedMode.NEW) null else recipe.seed
        return "${recipe.width}×${recipe.height} / ${recipe.sampler} / Seed ${seedLabel(seed)}"
    }

    /** NEW のときはシードを送らない。PC側が1枚ごとにランダムで選ぶ（通常生成と同じ）。 */
    fun request(recipe: Recipe, steps: Int, seedMode: SeedMode = SeedMode.SAME): AgentGenerationRequest =
        AgentGenerationRequest(
            prompt = recipe.prompt,
            negativePrompt = recipe.negativePrompt,
            width = recipe.width,
            height = recipe.height,
            steps = clampSteps(steps),
            samplerName = recipe.sampler,
            seed = if (seedMode == SeedMode.NEW) null else recipe.seed,
            tags = recipe.tags,
            cardStates = recipe.cardStates,
            randomPickedIds = recipe.randomPickedIds,
            randomEnabledCategories = recipe.randomEnabledCategories
        )

    /** 同じシードの再現は必ず1枚。シードを変えるときだけバッチ枚数へ広げる。 */
    fun requests(
        recipe: Recipe,
        steps: Int,
        seedMode: SeedMode,
        batch: Int
    ): List<AgentGenerationRequest> {
        if (seedMode == SeedMode.SAME) {
            return listOf(request(recipe, steps, SeedMode.SAME))
        }
        return List(clampBatch(batch)) { request(recipe, steps, SeedMode.NEW) }
    }
}
