package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.Axis
import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.SizePreset
import kotlin.math.roundToInt

/** 解像度バー、比率プリセット、比率ロックの連動状態。 */
internal object ResolutionControlPolicy {

    const val PRECISION_RADIUS = 100

    data class PrecisionBounds(val from: Int, val to: Int)

    enum class SnapMode {
        EIGHT_PIXELS,
        CUSTOM
    }

    enum class LockMode {
        UNLOCKED,
        LOCKED
    }

    data class State(
        val width: Int,
        val height: Int,
        val isLocked: Boolean,
        val ratioWidth: Int,
        val ratioHeight: Int
    )

    fun start(width: Int, height: Int): State = State(
        width = width,
        height = height,
        isLocked = false,
        ratioWidth = width,
        ratioHeight = height
    )

    /** ロック切替時点の寸法を比率基準にする。 */
    fun setLocked(state: State, mode: LockMode): State = state.copy(
        isLocked = mode == LockMode.LOCKED,
        ratioWidth = state.width,
        ratioHeight = state.height
    )

    /** 保存済みのロック比率を復元する。 */
    fun restoreLocked(state: State, ratioWidth: Int, ratioHeight: Int): State = state.copy(
        isLocked = true,
        ratioWidth = ratioWidth.takeIf { it in BuilderGenSettingPolicy.SIZE_MIN..BuilderGenSettingPolicy.SIZE_MAX }
            ?: state.width,
        ratioHeight = ratioHeight.takeIf { it in BuilderGenSettingPolicy.SIZE_MIN..BuilderGenSettingPolicy.SIZE_MAX }
            ?: state.height
    )

    /** プリセット選択時は、選んだ比率を新しい基準にする。 */
    fun selectRatio(state: State, width: Int, height: Int): State = state.copy(
        width = width,
        height = height,
        ratioWidth = width,
        ratioHeight = height
    )

    fun update(state: State, axis: Axis, raw: Int, snap: SnapMode, customStep: Int): State {
        if (axis != Axis.WIDTH && axis != Axis.HEIGHT) {
            return state
        }

        val step = stepFor(snap, customStep)
        val moved = snapSize(raw, step)
        val width = if (axis == Axis.WIDTH) moved else state.width
        val height = if (axis == Axis.HEIGHT) moved else state.height

        if (!state.isLocked) {
            return state.copy(
                width = width,
                height = height,
                ratioWidth = width,
                ratioHeight = height
            )
        }

        val baseMoved = if (axis == Axis.WIDTH) state.ratioWidth else state.ratioHeight
        val baseOther = if (axis == Axis.WIDTH) state.ratioHeight else state.ratioWidth
        val linked = if (baseMoved <= 0) {
            snapSize(baseOther, step)
        } else {
            val calculated = (moved.toDouble() * baseOther / baseMoved).roundToInt()
            snapSize(calculated, step)
        }

        return if (axis == Axis.WIDTH) {
            state.copy(width = moved, height = linked)
        } else {
            state.copy(width = linked, height = moved)
        }
    }

    fun reset(state: State): State = state.copy(
        width = BuilderGenSettingPolicy.DEFAULT_WIDTH,
        height = BuilderGenSettingPolicy.DEFAULT_HEIGHT,
        ratioWidth = BuilderGenSettingPolicy.DEFAULT_WIDTH,
        ratioHeight = BuilderGenSettingPolicy.DEFAULT_HEIGHT
    )

    fun matchesPresetRatio(state: State, preset: SizePreset): Boolean =
        state.ratioWidth.toLong() * preset.height == state.ratioHeight.toLong() * preset.width

    fun stepFor(mode: SnapMode, customStep: Int): Int = when (mode) {
        SnapMode.EIGHT_PIXELS -> BuilderGenSettingPolicy.SIZE_STEP
        SnapMode.CUSTOM -> sanitizeSnapStep(customStep)
    }

    fun sanitizeSnapStep(step: Int): Int = BuilderGenSettingPolicy.sanitizeSnapStep(step)

    fun precisionBounds(value: Int): PrecisionBounds {
        val center = value.coerceIn(BuilderGenSettingPolicy.SIZE_MIN, BuilderGenSettingPolicy.SIZE_MAX)
        return PrecisionBounds(
            from = (center - PRECISION_RADIUS).coerceAtLeast(BuilderGenSettingPolicy.SIZE_MIN),
            to = (center + PRECISION_RADIUS).coerceAtMost(BuilderGenSettingPolicy.SIZE_MAX)
        )
    }

    private fun snapSize(raw: Int, step: Int): Int {
        val snapped = (raw.toFloat() / step).roundToInt() * step
        return snapped.coerceIn(BuilderGenSettingPolicy.SIZE_MIN, BuilderGenSettingPolicy.SIZE_MAX)
    }
}
