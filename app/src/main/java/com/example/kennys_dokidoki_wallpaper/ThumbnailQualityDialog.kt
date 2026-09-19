package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.Context
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

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
        val sample = items.firstOrNull()?.request

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val example = TextView(activity).apply {
            setTextColor(android.graphics.Color.LTGRAY)
            textSize = 12f
        }
        // 「画質に従う」の表示は選んだ画質の既定ステップに追従させる。
        var followButton: RadioButton? = null
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

        container.addView(TextView(activity).apply { text = "画質"; setTextColor(android.graphics.Color.LTGRAY) })
        val qualityGroup = RadioGroup(activity)
        ThumbnailQualityPolicy.Quality.values().forEach { candidate ->
            qualityGroup.addView(
                RadioButton(activity).apply {
                    text = "${candidate.label}・既定${candidate.defaultSteps}step"
                    setTextColor(android.graphics.Color.WHITE)
                    isChecked = candidate == quality
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            quality = candidate
                            syncExample()
                        }
                    }
                }
            )
        }
        container.addView(qualityGroup)

        container.addView(TextView(activity).apply { text = "ステップ数"; setTextColor(android.graphics.Color.LTGRAY) })
        val stepsGroup = RadioGroup(activity)
        ThumbnailQualityPolicy.STEPS_CHOICES.forEach { candidate ->
            stepsGroup.addView(
                RadioButton(activity).apply {
                    text = ThumbnailQualityPolicy.stepsLabel(candidate, quality)
                    setTextColor(android.graphics.Color.WHITE)
                    isChecked = candidate == steps
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            steps = candidate
                            syncExample()
                        }
                    }
                }
            )
        }
        container.addView(stepsGroup)
        container.addView(example)
        syncExample()

        AlertDialog.Builder(activity, R.style.Theme_Kennys_dokidoki_wallpaper)
            .setTitle("サムネイル生成の画質")
            .setView(container)
            .setPositiveButton("生成開始") { _, _ ->
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
                onConfirm(adjusted)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun busyToast(activity: Activity) {
        Toast.makeText(activity, "別の生成が終わるまで待ってくれ。", Toast.LENGTH_SHORT).show()
    }
}
