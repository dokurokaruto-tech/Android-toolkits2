package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.widget.RadioGroup
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textview.MaterialTextView

/**
 * サムネイル生成の前におさえる画質／ステップ数。
 * 単体も一括もここを通るので、設定はこの1か所で足りる。
 */
object ThumbnailQualityDialog {

    fun show(
        activity: Activity,
        items: List<ThumbnailBindPolicy.Item>,
        onConfirm: (List<ThumbnailBindPolicy.Item>) -> Unit
    ) {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        var quality = ThumbnailQualityPolicy.qualityOf(
            prefs.getString(ThumbnailQualityPolicy.PREF_QUALITY, null)
        )
        var steps = prefs.getInt(
            ThumbnailQualityPolicy.PREF_STEPS,
            ThumbnailQualityPolicy.STEPS_FOLLOW_QUALITY
        )
        if (!ThumbnailQualityPolicy.STEPS_CHOICES.contains(steps)) {
            steps = ThumbnailQualityPolicy.STEPS_FOLLOW_QUALITY
        }
        val sample = items.firstOrNull()?.request

        // Material3 の浮きポップアップ。チェックの排他は RadioGroup だけに任せる。
        val (md3, view) = Md3PopupDialog.inflate(activity, R.layout.dialog_thumbnail_quality)
        val example = view.findViewById<MaterialTextView>(R.id.tv_quality_example)
        val qualityGroup = view.findViewById<RadioGroup>(R.id.rg_quality)
        val stepsGroup = view.findViewById<RadioGroup>(R.id.rg_steps)

        val qualityIds = mutableMapOf<Int, ThumbnailQualityPolicy.Quality>()
        ThumbnailQualityPolicy.Quality.values().forEach { candidate ->
            val button = MaterialRadioButton(md3).apply {
                text = "${candidate.label}・既定${candidate.defaultSteps}step"
            }
            qualityIds[button.id] = candidate
            qualityGroup.addView(button)
        }

        var followButton: MaterialRadioButton? = null
        val stepsIds = mutableMapOf<Int, Int>()
        ThumbnailQualityPolicy.STEPS_CHOICES.forEach { candidate ->
            val button = MaterialRadioButton(md3).apply {
                text = ThumbnailQualityPolicy.stepsLabel(candidate, quality)
            }
            if (candidate == ThumbnailQualityPolicy.STEPS_FOLLOW_QUALITY) followButton = button
            stepsIds[button.id] = candidate
            stepsGroup.addView(button)
        }

        fun syncExample() {
            followButton?.text = ThumbnailQualityPolicy.stepsLabel(
                ThumbnailQualityPolicy.STEPS_FOLLOW_QUALITY,
                quality
            )
            if (sample == null) return
            val (w, h) = ThumbnailQualityPolicy.scaledSize(sample.width, sample.height, quality)
            example.text = "${sample.width}×${sample.height} → ${w}×${h}・" +
                "${ThumbnailQualityPolicy.stepsOf(steps, quality)}step・アスペクト比は変えない"
        }

        qualityGroup.setOnCheckedChangeListener { _, checkedId ->
            qualityIds[checkedId]?.let {
                quality = it
                syncExample()
            }
        }
        stepsGroup.setOnCheckedChangeListener { _, checkedId ->
            stepsIds[checkedId]?.let {
                steps = it
                syncExample()
            }
        }

        // 初期チェックはグループに載せた後で一括。ボタン単位の isChecked はしない。
        qualityIds.entries.firstOrNull { it.value == quality }
            ?.let { qualityGroup.check(it.key) }
        stepsIds.entries.firstOrNull { it.value == steps }
            ?.let { stepsGroup.check(it.key) }
        syncExample()

        val dialog = Md3PopupDialog.showCompact(activity, view)
        view.findViewById<MaterialButton>(R.id.btn_quality_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btn_quality_start).setOnClickListener {
            prefs.edit()
                .putString(ThumbnailQualityPolicy.PREF_QUALITY, quality.name)
                .putInt(ThumbnailQualityPolicy.PREF_STEPS, steps)
                .apply()
            val adjusted = items.map { item ->
                val (w, h) = ThumbnailQualityPolicy.scaledSize(
                    item.request.width,
                    item.request.height,
                    quality
                )
                item.copy(
                    request = item.request.copy(
                        width = w,
                        height = h,
                        steps = ThumbnailQualityPolicy.stepsOf(steps, quality)
                    )
                )
            }
            dialog.dismiss()
            onConfirm(adjusted)
        }
    }

    fun busyToast(activity: Activity) {
        Toast.makeText(activity, "別の生成が終わるまで待ってくれ。", Toast.LENGTH_SHORT).show()
    }
}
