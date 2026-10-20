package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.Axis
import com.example.kennys_dokidoki_wallpaper.BuilderGenSettingPolicy.SizePreset
import kotlin.math.roundToInt

/** 解像度バー、比率プリセット、比率ロックの連動状態。 */
internal object ResolutionControlPolicy {

    enum class SnapMode(val step: Int) {
        EIGHT_PIXELS(BuilderGenSettingPolicy.SIZE_STEP),
        TEN_PIXELS(BuilderGenSettingPolicy.TEN_PIXEL_STEP)
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

    /** プリセット選択時は、選んだ比率を新しい基準にする。 */
    fun selectRatio(state: State, width: Int, height: Int): State = state.copy(
        width = width,
        height = height,
        ratioWidth = width,
        ratioHeight = height
    )

    fun update(state: State, axis: Axis, raw: Int, snap: SnapMode): State {
        if (axis != Axis.WIDTH && axis != Axis.HEIGHT) {
            return state
        }

        val moved = snapSize(raw, snap)
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
            snapSize(baseOther, snap)
        } else {
            val calculated = (moved.toDouble() * baseOther / baseMoved).roundToInt()
            snapSize(calculated, snap)
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

    private fun snapSize(raw: Int, snap: SnapMode): Int =
        (raw.toFloat() / snap.step).roundToInt()
            .times(snap.step)
            .coerceIn(BuilderGenSettingPolicy.SIZE_MIN, BuilderGenSettingPolicy.SIZE_MAX)
}
