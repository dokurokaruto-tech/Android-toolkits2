package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import kotlin.math.roundToInt

/**
 * Builder 上部4ボタンを開いたときのポップアップ。
 * 名前は Dialog だが全画面ではなく、他の編集ポップアップと同じ浮きカード。
 * 解像度は ResolutionControlPolicy、他の数値は BuilderGenSettingPolicy で扱う。
 * 生成設定は「適用」まで下書き。スナップ設定は切替・入力時に保存する。
 */
object GenSettingDialog {

    private const val SETTINGS_PREFERENCES = "settings"
    private const val SNAP_ENABLED_PREFERENCE = "gen_resolution_snap_enabled"
    private const val LEGACY_SNAP_ENABLED_PREFERENCE = "gen_resolution_snap_10"
    private const val SNAP_STEP_PREFERENCE = "gen_resolution_snap_step"
    private const val DEFAULT_SNAP_ENABLED = true

    /** 入力欄の桁数。枚数の上限ではなく Int の溢れ防止。 */
    private const val MAX_DIGITS = 7

    fun show(
        activity: Activity,
        kind: BuilderGenSettingPolicy.Kind,
        current: BuilderGenSettingPolicy.Settings,
        onApply: (BuilderGenSettingPolicy.Settings) -> Unit
    ) {
        val (md3, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_gen_setting)
        view.findViewById<MaterialTextView>(R.id.tv_gen_setting_title).text = kind.title
        view.findViewById<MaterialTextView>(R.id.tv_gen_setting_hint).text = kind.hint

        val preview = view.findViewById<MaterialTextView>(R.id.tv_gen_setting_preview)
        val rowsHost = view.findViewById<LinearLayout>(R.id.ll_gen_setting_rows)
        val chipGroup = view.findViewById<ChipGroup>(R.id.cg_gen_setting_chips)
        val chipStrip = view.findViewById<View>(R.id.hsv_gen_setting_chips)
        val lockRatio = view.findViewById<MaterialSwitch>(R.id.sw_gen_setting_lock)
        val snapEnabled = view.findViewById<MaterialSwitch>(R.id.sw_gen_setting_snap)
        val snapStepRow = view.findViewById<View>(R.id.ll_gen_setting_snap_step)
        val snapStepField = view.findViewById<EditText>(R.id.et_gen_setting_snap_step)
        val settingsPrefs = activity.getSharedPreferences(SETTINGS_PREFERENCES, Context.MODE_PRIVATE)
        snapEnabled.isChecked = settingsPrefs.getBoolean(
            SNAP_ENABLED_PREFERENCE,
            settingsPrefs.getBoolean(LEGACY_SNAP_ENABLED_PREFERENCE, DEFAULT_SNAP_ENABLED)
        )
        snapStepField.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
        var snapStep = BuilderGenSettingPolicy.sanitizeSnapStep(
            settingsPrefs.getInt(SNAP_STEP_PREFERENCE, BuilderGenSettingPolicy.DEFAULT_SNAP_STEP)
        )
        snapStepField.setText(snapStep.toString())

        val rows = mutableMapOf<BuilderGenSettingPolicy.Axis, Row>()
        val choices = choicesOf(kind)
        val chipIds = mutableListOf<Int>()
        var draft = BuilderGenSettingPolicy.sanitize(current)
        var resolution = ResolutionControlPolicy.start(draft.width, draft.height)

        val accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        val danger = MaterialColors.getColor(view, com.google.android.material.R.attr.colorError)

        fun syncResolution(state: ResolutionControlPolicy.State) {
            resolution = state
            draft = draft.copy(width = state.width, height = state.height)
        }

        fun snapModeFor(source: ChangeSource): ResolutionControlPolicy.SnapMode =
            if (source != ChangeSource.FIELD && snapEnabled.isChecked) {
                ResolutionControlPolicy.SnapMode.CUSTOM
            } else {
                ResolutionControlPolicy.SnapMode.EIGHT_PIXELS
            }

        fun setSnapStep(raw: Int) {
            snapStep = BuilderGenSettingPolicy.sanitizeSnapStep(raw)
            if (snapStepField.text.toString() != snapStep.toString()) {
                snapStepField.setText(snapStep.toString())
            }
            settingsPrefs.edit().putInt(SNAP_STEP_PREFERENCE, snapStep).commit()
        }

        fun sync(keepFieldFor: BuilderGenSettingPolicy.Axis?) {
            val limit = BuilderGenSettingPolicy.AGENT_JOB_IMAGE_LIMIT
            val overBatch = kind == BuilderGenSettingPolicy.Kind.BATCH && draft.batch > limit
            val label = BuilderGenSettingPolicy.preview(kind, draft)

            // 枚数はアプリ側で絞らない。PCエージェントの受け付け上限は警告だけで伝える。
            preview.text = if (overBatch) "$label（PC側は${limit}枚まで）" else label
            preview.setTextColor(if (overBatch) danger else accent)
            rows.forEach { (axis, row) ->
                val value = BuilderGenSettingPolicy.valueOf(draft, axis)
                row.show(value, updateField = axis != keepFieldFor)
            }

            val index = choices.indexOfFirst { choice ->
                if (kind == BuilderGenSettingPolicy.Kind.RESOLUTION && lockRatio.isChecked) {
                    choice.matchesRatio?.invoke(resolution) == true
                } else {
                    choice.matches(draft)
                }
            }
            if (index < 0) {
                chipGroup.clearCheck()
            } else if (chipGroup.checkedChipId != chipIds[index]) {
                chipGroup.check(chipIds[index])
            }
        }

        fun commit(axis: BuilderGenSettingPolicy.Axis, raw: Int?, source: ChangeSource) {
            if (raw == null) {
                return
            }

            if (axis == BuilderGenSettingPolicy.Axis.WIDTH || axis == BuilderGenSettingPolicy.Axis.HEIGHT) {
                syncResolution(ResolutionControlPolicy.update(resolution, axis, raw, snapModeFor(source), snapStep))
            } else {
                draft = BuilderGenSettingPolicy.with(draft, axis, BuilderGenSettingPolicy.clamp(axis, raw))
            }

            sync(if (source == ChangeSource.FIELD) axis else null)
        }

        fun nudge(axis: BuilderGenSettingPolicy.Axis, direction: Int) {
            val step = if (axis == BuilderGenSettingPolicy.Axis.WIDTH || axis == BuilderGenSettingPolicy.Axis.HEIGHT) {
                ResolutionControlPolicy.stepFor(snapModeFor(ChangeSource.NUDGE), snapStep)
            } else {
                BuilderGenSettingPolicy.swipeStep(axis)
            }
            val value = BuilderGenSettingPolicy.valueOf(draft, axis) + step * direction
            commit(axis, value, ChangeSource.NUDGE)
        }

        BuilderGenSettingPolicy.axesOf(kind).forEach { axis ->
            val rowView = LayoutInflater.from(md3).inflate(R.layout.item_gen_setting_number, rowsHost, false)
            rowView.findViewById<MaterialTextView>(R.id.tv_gen_number_label).text = axis.label
            rowView.findViewById<MaterialTextView>(R.id.tv_gen_number_unit).text = axis.unit

            val field = rowView.findViewById<EditText>(R.id.et_gen_number_value)
            field.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(MAX_DIGITS))

            val row = Row(axis, field, rowView.findViewById<Slider>(R.id.sl_gen_number_value))
            rows[axis] = row
            rowsHost.addView(rowView)

            field.addTextChangedListener {
                if (!row.busy) {
                    commit(axis, field.text.toString().toIntOrNull(), ChangeSource.FIELD)
                }
            }
            row.slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !row.busy) {
                    commit(axis, value.roundToInt(), ChangeSource.SLIDER)
                }
            }
            rowView.findViewById<MaterialButton>(R.id.btn_gen_number_minus)
                .setOnClickListener { nudge(axis, -1) }
            rowView.findViewById<MaterialButton>(R.id.btn_gen_number_plus)
                .setOnClickListener { nudge(axis, 1) }
        }

        if (choices.isEmpty()) {
            chipStrip.visibility = View.GONE
        } else {
            chipStrip.visibility = View.VISIBLE
            choices.forEach { choice ->
                val chip = Chip(md3).apply {
                    id = View.generateViewId()
                    text = choice.label
                    isCheckable = true
                    setOnClickListener {
                        draft = choice.updated(draft)
                        if (kind == BuilderGenSettingPolicy.Kind.RESOLUTION) {
                            syncResolution(ResolutionControlPolicy.selectRatio(resolution, draft.width, draft.height))
                        }
                        sync(null)
                    }
                }
                chipIds += chip.id
                chipGroup.addView(chip)
            }
        }

        val isResolution = kind == BuilderGenSettingPolicy.Kind.RESOLUTION
        lockRatio.visibility = if (isResolution) View.VISIBLE else View.GONE
        snapEnabled.visibility = if (isResolution) View.VISIBLE else View.GONE
        snapStepRow.visibility = if (isResolution) View.VISIBLE else View.GONE
        lockRatio.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) {
                ResolutionControlPolicy.LockMode.LOCKED
            } else {
                ResolutionControlPolicy.LockMode.UNLOCKED
            }
            syncResolution(ResolutionControlPolicy.setLocked(resolution, mode))
            sync(null)
        }
        snapEnabled.setOnCheckedChangeListener { _, checked ->
            settingsPrefs.edit()
                .putBoolean(SNAP_ENABLED_PREFERENCE, checked)
                .putInt(SNAP_STEP_PREFERENCE, snapStep)
                .commit()
        }
        snapStepField.addTextChangedListener { editable ->
            val entered = editable?.toString()?.toIntOrNull() ?: return@addTextChangedListener
            if (entered !in BuilderGenSettingPolicy.SNAP_STEP_MIN..BuilderGenSettingPolicy.SNAP_STEP_MAX) {
                return@addTextChangedListener
            }
            snapStep = entered
            settingsPrefs.edit().putInt(SNAP_STEP_PREFERENCE, entered).apply()
        }
        snapStepField.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val entered = snapStepField.text.toString().toIntOrNull()
                setSnapStep(entered ?: snapStep)
            }
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_snap_minus).setOnClickListener {
            setSnapStep(snapStep - 1)
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_snap_plus).setOnClickListener {
            setSnapStep(snapStep + 1)
        }

        val dialog = Md3PopupDialog.showCompact(activity, view)

        // 入力欄がキーボードに沈まないように寄せる。
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        view.findViewById<MaterialButton>(R.id.btn_gen_setting_reset).setOnClickListener {
            draft = BuilderGenSettingPolicy.reset(kind, draft)
            if (isResolution) {
                syncResolution(ResolutionControlPolicy.reset(resolution))
            }
            sync(null)
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_apply).setOnClickListener {
            if (isResolution) {
                snapStep = BuilderGenSettingPolicy.sanitizeSnapStep(
                    snapStepField.text.toString().toIntOrNull()
                )
                if (snapStepField.text.toString() != snapStep.toString()) {
                    snapStepField.setText(snapStep.toString())
                }
                settingsPrefs.edit()
                    .putBoolean(SNAP_ENABLED_PREFERENCE, snapEnabled.isChecked)
                    .putInt(SNAP_STEP_PREFERENCE, snapStep)
                    .commit()
            }
            dialog.dismiss()
            onApply(draft)
        }

        sync(null)
    }

    private enum class ChangeSource {
        FIELD,
        SLIDER,
        NUDGE
    }

    /** 数値1項目ぶん。スライダーと入力欄を行の中で往復させる。 */
    private class Row(
        val axis: BuilderGenSettingPolicy.Axis,
        val field: EditText,
        val slider: Slider
    ) {
        /** 行自身が書き換えている印。listener の発火ループを止める。 */
        var busy = false
            private set

        private var swipeTop = 0f

        fun show(value: Int, updateField: Boolean) {
            busy = true

            val bottom = BuilderGenSettingPolicy.swipeMin(axis).toFloat()
            val step = BuilderGenSettingPolicy.SLIDER_PIXEL_STEP.toFloat()

            // 枚数を範囲越えで指定しても切り捨てない。スワイプ範囲のほうが伸びる。
            swipeTop = maxOf(swipeTop, BuilderGenSettingPolicy.swipeMax(axis, value).toFloat())

            // Slider は range → value → stepSize の順でないと内部検証で例外になる。
            // stepSize は setter の引数が nullable Float なのでプロパティ代入できない。
            slider.valueTo = swipeTop
            slider.value = bottom
            slider.valueFrom = bottom
            slider.setStepSize(step)
            slider.value = value.toFloat()

            if (updateField && field.text.toString().toIntOrNull() != value) {
                field.setText(value.toString())
            }
            busy = false
        }
    }

    /** 横スワイプで流す選択肢。解像度の比率とサンプラーだけ。 */
    private class Choice(
        val label: String,
        val matches: (BuilderGenSettingPolicy.Settings) -> Boolean,
        val updated: (BuilderGenSettingPolicy.Settings) -> BuilderGenSettingPolicy.Settings,
        val matchesRatio: ((ResolutionControlPolicy.State) -> Boolean)? = null
    )

    private fun choicesOf(kind: BuilderGenSettingPolicy.Kind): List<Choice> {
        if (kind == BuilderGenSettingPolicy.Kind.RESOLUTION) {
            return BuilderGenSettingPolicy.SIZE_PRESETS.map { preset ->
                Choice(
                    label = preset.label,
                    matches = { it.width == preset.width && it.height == preset.height },
                    updated = { it.copy(width = preset.width, height = preset.height) },
                    matchesRatio = { ResolutionControlPolicy.matchesPresetRatio(it, preset) }
                )
            }
        }
        if (kind != BuilderGenSettingPolicy.Kind.SAMPLER) {
            return emptyList()
        }
        return BuilderGenSettingPolicy.SAMPLERS.map { name ->
            Choice(
                label = name,
                matches = { it.sampler == name },
                updated = { it.copy(sampler = name) }
            )
        }
    }
}
