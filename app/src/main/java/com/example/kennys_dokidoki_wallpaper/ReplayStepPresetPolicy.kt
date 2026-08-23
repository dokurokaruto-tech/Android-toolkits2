package com.example.kennys_dokidoki_wallpaper

/**
 * 再生成ダイアログのステップ数チップ。端末に残して次の画面でも同じボタンが出る。
 */
object ReplayStepPresetPolicy {
    const val PREFS_NAME = "settings"
    const val KEY = "replay_step_presets"
    const val MAX_PRESETS = 12
    val DEFAULTS = listOf(20, 30, 50)

    fun parse(raw: String?): List<Int> {
        if (raw == null) return DEFAULTS
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';', ' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .map { GeneratedImageReplayPolicy.clampSteps(it) }
            .distinct()
            .take(MAX_PRESETS)
    }

    fun encode(values: List<Int>): String =
        normalize(values).joinToString(",")

    fun normalize(values: List<Int>): List<Int> =
        values.map { GeneratedImageReplayPolicy.clampSteps(it) }
            .distinct()
            .take(MAX_PRESETS)

    fun add(values: List<Int>, steps: Int): List<Int> {
        val next = GeneratedImageReplayPolicy.clampSteps(steps)
        if (next in values) return normalize(values)
        return normalize(values + next)
    }

    fun remove(values: List<Int>, steps: Int): List<Int> {
        val target = GeneratedImageReplayPolicy.clampSteps(steps)
        return normalize(values.filter { it != target })
    }
}
