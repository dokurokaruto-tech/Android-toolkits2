package com.example.kennys_dokidoki_wallpaper

/**
 * カード／プリセットのサムネイル生成の画質とステップ数の決め方。
 * アスペクト比は基本生成のまま。辺だけを8の倍数へ丸める（SD APIの制約）。
 */
object ThumbnailQualityPolicy {
    const val PREF_QUALITY = "thumbnail_quality"
    const val PREF_STEPS = "thumbnail_steps"

    /** ステップ数を画質の既定に任せる印。 */
    const val STEPS_FOLLOW_QUALITY = 0

    private const val ALIGN = 8
    private const val MIN_SIZE = 256

    enum class Quality(val scale: Float, val defaultSteps: Int, val label: String) {
        HIGH(1.0f, 30, "高（元の解像度）"),
        MEDIUM(0.7f, 20, "中（解像度70%）"),
        LOW(0.5f, 12, "低（解像度50%）")
    }

    val DEFAULT_QUALITY = Quality.MEDIUM
    val STEPS_CHOICES = listOf(STEPS_FOLLOW_QUALITY, 12, 20, 30, 40)

    fun qualityOf(raw: String?): Quality =
        Quality.values().firstOrNull { it.name == raw } ?: DEFAULT_QUALITY

    /** 0 は「画質に従う」。正の値はユーザーの明示指定が勝つ。 */
    fun stepsOf(raw: Int, quality: Quality): Int =
        if (raw > 0) raw else quality.defaultSteps

    fun stepsLabel(raw: Int, quality: Quality): String =
        if (raw == STEPS_FOLLOW_QUALITY) "画質に従う（${quality.defaultSteps}）" else "$raw"

    /** アスペクト比を保ちつつ各辺を8の倍数へ。壊れた入力は触らない。 */
    fun scaledSize(width: Int, height: Int, quality: Quality): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        return align(Math.round(width * quality.scale)) to
            align(Math.round(height * quality.scale))
    }

    private fun align(value: Int): Int =
        (Math.round(value.toFloat() / ALIGN) * ALIGN).coerceAtLeast(MIN_SIZE)
}
