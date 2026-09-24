package com.example.kennys_dokidoki_wallpaper

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prompt Builder 上部4ボタンの設定値そのもの。
 * ポップアップのスワイプ（Slider）と数値入力は、必ずここを通って丸める。
 *
 *   [数値入力] ─┐
 *              ├─> clamp(Axis) ─> Settings ─> 生成リクエスト
 *   [スワイプ] ─┘
 */
object BuilderGenSettingPolicy {

    /** 4ボタンそれぞれの画面。 */
    enum class Kind(val title: String, val hint: String) {
        RESOLUTION("解像度", "スワイプでざっくり、数字で正確に。8の倍数へ揃え、64〜4096に収めます。"),
        STEPS("ステップ数", "多いほど詰むが遅くなる。1〜100。"),
        BATCH("バッチカウント", "一度に生成する枚数。上限はない。多いほど時間がかかる。"),
        SAMPLER("サンプラー", "横にスワイプして一覧から選ぶ。")
    }

    /** 数値で触れる軸。サンプラーは選択肢なのでここには入らない。 */
    enum class Axis(val label: String, val unit: String) {
        WIDTH("横", "px"),
        HEIGHT("縦", "px"),
        STEPS("ステップ", "step"),
        BATCH("枚数", "枚")
    }

    data class Settings(
        val width: Int,
        val height: Int,
        val steps: Int,
        val batch: Int,
        val sampler: String
    )

    data class SizePreset(val label: String, val width: Int, val height: Int)

    // SD API が受け付ける辺の範囲。8の倍数でないと弾かれる。
    const val SIZE_MIN = 64
    const val SIZE_MAX = 4096
    const val SIZE_STEP = 8

    const val STEPS_MIN = 1
    const val STEPS_MAX = 100

    // 枚数に上限は作らない。スワイプの初期範囲だけ 10 枚にして、超える入力では範囲を伸ばす。
    const val BATCH_MIN = 1
    const val BATCH_SWIPE_SPAN = 10

    /** PC エージェント側が 1 ジョブで引き受ける枚数。超えた指定はアプリで通っても agent が断る。 */
    const val AGENT_JOB_IMAGE_LIMIT = 1000

    const val DEFAULT_WIDTH = 720
    const val DEFAULT_HEIGHT = 1280
    const val DEFAULT_STEPS = 20
    const val DEFAULT_BATCH = 1
    const val DEFAULT_SAMPLER = "Euler a"

    val SIZE_PRESETS = listOf(
        SizePreset("9:16", 720, 1280),
        SizePreset("16:9", 1280, 720),
        SizePreset("1:1", 512, 512),
        SizePreset("2:3", 512, 768),
        SizePreset("3:2", 768, 512)
    )

    val SAMPLERS = listOf(
        "Euler a", "Euler", "LMS", "Heun", "DPM2", "DPM2 a", "DPM++ 2S a",
        "DPM++ 2M", "DPM++ SDE", "DPM++ 2M Karras", "DPM++ SDE Karras", "DDIM"
    )

    fun axesOf(kind: Kind): List<Axis> = when (kind) {
        Kind.RESOLUTION -> listOf(Axis.WIDTH, Axis.HEIGHT)
        Kind.STEPS -> listOf(Axis.STEPS)
        Kind.BATCH -> listOf(Axis.BATCH)
        Kind.SAMPLER -> emptyList()
    }

    /** 生の入力値を、その軸の規則（8の倍数・範囲）へ落とす。読めない値は既定へ。 */
    fun clamp(axis: Axis, raw: Int?): Int = when (axis) {
        Axis.WIDTH -> snapSize(raw, DEFAULT_WIDTH)
        Axis.HEIGHT -> snapSize(raw, DEFAULT_HEIGHT)
        Axis.STEPS -> (raw ?: DEFAULT_STEPS).coerceIn(STEPS_MIN, STEPS_MAX)
        Axis.BATCH -> (raw ?: DEFAULT_BATCH).coerceAtLeast(BATCH_MIN)
    }

    fun swipeMin(axis: Axis): Int = when (axis) {
        Axis.WIDTH, Axis.HEIGHT -> SIZE_MIN
        Axis.STEPS -> STEPS_MIN
        Axis.BATCH -> BATCH_MIN
    }

    fun swipeStep(axis: Axis): Int = when (axis) {
        Axis.WIDTH, Axis.HEIGHT -> SIZE_STEP
        Axis.STEPS, Axis.BATCH -> 1
    }

    /** 入力値がスワイプ範囲を超えたら、範囲のほうが伸びる。値を切り捨てはしない。 */
    fun swipeMax(axis: Axis, value: Int): Int = when (axis) {
        Axis.WIDTH, Axis.HEIGHT -> SIZE_MAX
        Axis.STEPS -> STEPS_MAX
        Axis.BATCH -> max(BATCH_SWIPE_SPAN, value)
    }

    fun with(settings: Settings, axis: Axis, value: Int): Settings = when (axis) {
        Axis.WIDTH -> settings.copy(width = value)
        Axis.HEIGHT -> settings.copy(height = value)
        Axis.STEPS -> settings.copy(steps = value)
        Axis.BATCH -> settings.copy(batch = value)
    }

    fun valueOf(settings: Settings, axis: Axis): Int = when (axis) {
        Axis.WIDTH -> settings.width
        Axis.HEIGHT -> settings.height
        Axis.STEPS -> settings.steps
        Axis.BATCH -> settings.batch
    }

    /** 比率固定で連動する相手軸。1項目だけの設定には無い。 */
    fun pairedAxis(axis: Axis): Axis? = when (axis) {
        Axis.WIDTH -> Axis.HEIGHT
        Axis.HEIGHT -> Axis.WIDTH
        Axis.STEPS, Axis.BATCH -> null
    }

    /** ポップアップの「既定に戻す」。その画面の項目だけ直す。 */
    fun reset(kind: Kind, settings: Settings): Settings = when (kind) {
        Kind.RESOLUTION -> settings.copy(width = DEFAULT_WIDTH, height = DEFAULT_HEIGHT)
        Kind.STEPS -> settings.copy(steps = DEFAULT_STEPS)
        Kind.BATCH -> settings.copy(batch = DEFAULT_BATCH)
        Kind.SAMPLER -> settings.copy(sampler = DEFAULT_SAMPLER)
    }

    /** 比率固定でもう片方の辺を追随させる。基準が壊れていたら相手辺をそのまま返す。 */
    fun linkedSize(moved: Int, baseMoved: Int, baseOther: Int): Int {
        if (baseMoved <= 0) {
            return baseOther
        }
        return snapSize((moved.toFloat() * baseOther / baseMoved).roundToInt(), baseOther)
    }

    /** プリセット読み込みや保存値の補正。枚数は上へは直さない。 */
    fun sanitize(settings: Settings): Settings = Settings(
        width = clamp(Axis.WIDTH, settings.width),
        height = clamp(Axis.HEIGHT, settings.height),
        steps = clamp(Axis.STEPS, settings.steps),
        batch = clamp(Axis.BATCH, settings.batch),
        sampler = settings.sampler.ifBlank { DEFAULT_SAMPLER }
    )

    /** ボタンにもポップアップにも出る、その設定一行分の表示。 */
    fun preview(kind: Kind, settings: Settings): String = when (kind) {
        Kind.RESOLUTION -> "${settings.width} x ${settings.height}"
        Kind.STEPS -> "Steps: ${settings.steps}"
        Kind.BATCH -> "Batch: ${settings.batch}"
        Kind.SAMPLER -> settings.sampler
    }

    private fun snapSize(raw: Int?, fallback: Int): Int {
        if (raw == null) {
            return fallback
        }
        val aligned = (raw.toFloat() / SIZE_STEP).roundToInt() * SIZE_STEP
        return aligned.coerceIn(SIZE_MIN, SIZE_MAX)
    }
}
