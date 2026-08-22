package com.example.kennys_dokidoki_wallpaper

/** Exact comparison used to decide which preset card receives the purple active border. */
object PresetMatchPolicy {
    fun matches(
        preset: Preset,
        selectionLevels: Map<String, Int>,
        randomEnabledCategories: Set<String>,
        width: Int,
        height: Int,
        steps: Int,
        batchCount: Int,
        sampler: String
    ): Boolean {
        return preset.activePromptStates == selectionLevels &&
            preset.randomEnabledCategories == randomEnabledCategories &&
            preset.width == width &&
            preset.height == height &&
            preset.steps == steps &&
            preset.batchCount == batchCount &&
            preset.sampler == sampler
    }
}
