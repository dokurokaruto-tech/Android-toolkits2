package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
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
 * スワイプ（Slider）でも数値入力でも、値は必ず BuilderGenSettingPolicy を通る。
 * 確定は「適用」をタップしたときだけ。閉じれば何も変わらない。
 */
object GenSettingDialog {

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

        val rows = mutableMapOf<BuilderGenSettingPolicy.Axis, Row>()
        val choices = choicesOf(kind)
        val chipIds = mutableListOf<Int>()
        var draft = BuilderGenSettingPolicy.sanitize(current)

        val accent = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        val danger = MaterialColors.getColor(view, com.google.android.material.R.attr.colorError)

        // 比率固定の基準辺。ロックを切っている間は現在のサイズへ追従させる。
        var baseWidth = draft.width
        var baseHeight = draft.height

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

            val index = choices.indexOfFirst { it.matches(draft) }
            if (index < 0) {
                chipGroup.clearCheck()
            } else if (chipGroup.checkedChipId != chipIds[index]) {
                chipGroup.check(chipIds[index])
            }
        }

        // 比率ロック中なら、動かした辺から相手辺を逆算して従える。
        fun followRatio(axis: BuilderGenSettingPolicy.Axis) {
            val other = BuilderGenSettingPolicy.pairedAxis(axis)
            if (other == null || !lockRatio.isChecked) {
                baseWidth = draft.width
                baseHeight = draft.height
                return
            }
            val widthFirst = axis == BuilderGenSettingPolicy.Axis.WIDTH
            val moved = if (widthFirst) baseWidth else baseHeight
            val anchor = if (widthFirst) baseHeight else baseWidth
            val value = BuilderGenSettingPolicy.valueOf(draft, axis)
            val linked = BuilderGenSettingPolicy.linkedSize(value, moved, anchor)
            draft = BuilderGenSettingPolicy.with(draft, other, linked)
        }

        /** 数値を1軸ぶん確定する。typed=true は入力欄側なので書き戻さない。 */
        fun commit(axis: BuilderGenSettingPolicy.Axis, raw: Int?, typed: Boolean) {
            if (raw == null) {
                return
            }
            draft = BuilderGenSettingPolicy.with(draft, axis, BuilderGenSettingPolicy.clamp(axis, raw))
            followRatio(axis)
            sync(if (typed) axis else null)
        }

        // スワイプが粗くなる大きな枚数や、8px刻みの境界用に細かく動かす口。
        fun nudge(axis: BuilderGenSettingPolicy.Axis, direction: Int) {
            val step = BuilderGenSettingPolicy.swipeStep(axis) * direction
            commit(axis, BuilderGenSettingPolicy.valueOf(draft, axis) + step, typed = false)
        }

        BuilderGenSettingPolicy.axesOf(kind).forEach { axis ->
            val rowView = LayoutInflater.from(md3).inflate(R.layout.item_gen_setting_number, rowsHost, false)
            rowView.findViewById<MaterialTextView>(R.id.tv_gen_number_label).text = axis.label
            rowView.findViewById<MaterialTextView>(R.id.tv_gen_number_unit).text = axis.unit

            val field = rowView.findViewById<EditText>(R.id.et_gen_number_value)
            field.filters = arrayOf(InputFilter.LengthFilter(MAX_DIGITS))

            val row = Row(axis, field, rowView.findViewById<Slider>(R.id.sl_gen_number_value))
            rows[axis] = row
            rowsHost.addView(rowView)

            field.addTextChangedListener {
                if (!row.busy) {
                    commit(axis, field.text.toString().toIntOrNull(), typed = true)
                }
            }
            row.slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser && !row.busy) {
                    commit(axis, value.roundToInt(), typed = false)
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
                        sync(null)
                    }
                }
                chipIds += chip.id
                chipGroup.addView(chip)
            }
        }

        lockRatio.visibility =
            if (kind == BuilderGenSettingPolicy.Kind.RESOLUTION) View.VISIBLE else View.GONE

        val dialog = Md3PopupDialog.showCompact(activity, view)

        // 入力欄がキーボードに沈まないように寄せる。
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        view.findViewById<MaterialButton>(R.id.btn_gen_setting_reset).setOnClickListener {
            draft = BuilderGenSettingPolicy.reset(kind, draft)
            sync(null)
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_gen_setting_apply).setOnClickListener {
            dialog.dismiss()
            onApply(draft)
        }

        sync(null)
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
            val step = BuilderGenSettingPolicy.swipeStep(axis).toFloat()

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
        val updated: (BuilderGenSettingPolicy.Settings) -> BuilderGenSettingPolicy.Settings
    )

    private fun choicesOf(kind: BuilderGenSettingPolicy.Kind): List<Choice> {
        if (kind == BuilderGenSettingPolicy.Kind.RESOLUTION) {
            return BuilderGenSettingPolicy.SIZE_PRESETS.map { preset ->
                Choice(preset.label, { it.width == preset.width && it.height == preset.height }) {
                    it.copy(width = preset.width, height = preset.height)
                }
            }
        }
        if (kind != BuilderGenSettingPolicy.Kind.SAMPLER) {
            return emptyList()
        }
        return BuilderGenSettingPolicy.SAMPLERS.map { name ->
            Choice(name, { it.sampler == name }) { it.copy(sampler = name) }
        }
    }
}
