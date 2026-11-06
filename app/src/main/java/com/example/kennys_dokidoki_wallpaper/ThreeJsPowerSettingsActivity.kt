package com.example.kennys_dokidoki_wallpaper

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

private enum class ChipSelectionState {
    SELECTED,
    UNSELECTED
}

private data class PowerChoiceItem<T>(
    val value: T,
    val title: String,
    val detail: String
)

class ThreeJsPowerSettingsActivity : AppCompatActivity() {

    private lateinit var subtitleText: TextView
    private lateinit var sectionsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG_ROOT_COLOR
        window.navigationBarColor = BG_ROOT_COLOR
        setContentView(R.layout.activity_threejs_power_settings)

        subtitleText = findViewById(R.id.txt_threejs_power_subtitle)
        sectionsContainer = findViewById(R.id.threejs_power_sections_container)
        findViewById<ImageButton>(R.id.btn_threejs_power_back).setOnClickListener {
            finish()
        }
        rebuildContent()
    }

    override fun onResume() {
        super.onResume()
        rebuildContent()
    }

    private fun rebuildContent() {
        val config = ThreeJsPowerStore.loadConfig(this)
        val preset = ThreeJsPowerPolicy.detectPreset(config)
        val effective = ThreeJsPowerStore.resolvePlan(this, HomeTouchState.ACTIVE)

        subtitleText.text = "${preset.badge} · ${ThreeJsPowerPolicy.formatSummary(config)}"
        sectionsContainer.removeAllViews()

        sectionsContainer.addView(buildStatusBanner(config, effective))

        sectionsContainer.addView(buildSectionTitle("一括プリセット"))
        sectionsContainer.addView(buildPresetRow(preset))

        sectionsContainer.addView(buildSectionTitle("背面・スリープ時のメモリ＆プロセス制御"))
        sectionsContainer.addView(
            buildSettingCard(
                label = "他のアプリが前面に出たとき",
                valueText = config.backgroundMode.title,
                detailText = config.backgroundMode.detail
            ) {
                openEnumSelector(
                    dialogTitle = "他のアプリが前面に出たとき",
                    current = config.backgroundMode,
                    items = ThreeJsBackgroundMode.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(backgroundMode = chosen))
                }
            }
        )

        val idlePurgeNote = if (config.backgroundMode == ThreeJsBackgroundMode.DEEP_STOP_PURGE_ALL) {
            "※「完全停止・全キャッシュ破棄」選択中のため、背面移行後すぐに完全破棄されます"
        } else {
            config.idlePurgeDelay.detail
        }
        sectionsContainer.addView(
            buildSettingCard(
                label = "非表示が続いたときの自動完全破棄",
                valueText = config.idlePurgeDelay.title,
                detailText = idlePurgeNote
            ) {
                openEnumSelector(
                    dialogTitle = "非表示が続いたときの自動完全破棄",
                    current = config.idlePurgeDelay,
                    items = ThreeJsIdlePurgeDelay.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(idlePurgeDelay = chosen))
                }
            }
        )

        sectionsContainer.addView(
            buildSettingCard(
                label = "画面消灯（スリープ）時の動作",
                valueText = config.screenOffPolicy.title,
                detailText = config.screenOffPolicy.detail
            ) {
                openEnumSelector(
                    dialogTitle = "画面消灯（スリープ）時の動作",
                    current = config.screenOffPolicy,
                    items = ThreeJsScreenOffPolicy.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(screenOffPolicy = chosen))
                }
            }
        )

        sectionsContainer.addView(buildSectionTitle("描画負荷・GPUパイプライン削減"))
        sectionsContainer.addView(
            buildSettingCard(
                label = "最大フレームレート制限",
                valueText = config.frameRateCap.title,
                detailText = config.frameRateCap.detail
            ) {
                openEnumSelector(
                    dialogTitle = "最大フレームレート制限",
                    current = config.frameRateCap,
                    items = ThreeJsFrameRateCap.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(frameRateCap = chosen))
                }
            }
        )

        sectionsContainer.addView(
            buildSettingCard(
                label = "内部レンダリング解像度・DPR",
                valueText = config.resolutionTier.title,
                detailText = config.resolutionTier.detail
            ) {
                openEnumSelector(
                    dialogTitle = "内部レンダリング解像度・DPR",
                    current = config.resolutionTier,
                    items = ThreeJsResolutionTier.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(resolutionTier = chosen))
                }
            }
        )

        sectionsContainer.addView(
            buildSettingCard(
                label = "GPUシェーダー・AA・シャドウ制御",
                valueText = config.gpuQuality.title,
                detailText = config.gpuQuality.detail
            ) {
                openEnumSelector(
                    dialogTitle = "GPUシェーダー・AA・シャドウ制御",
                    current = config.gpuQuality,
                    items = ThreeJsGpuQuality.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(gpuQuality = chosen))
                }
            }
        )

        sectionsContainer.addView(buildSectionTitle("スマート自動休止・OS環境連動"))
        sectionsContainer.addView(
            buildSettingCard(
                label = "ホーム画面の無操作アイドリング休止",
                valueText = config.homeIdleMode.title,
                detailText = config.homeIdleMode.detail
            ) {
                openEnumSelector(
                    dialogTitle = "ホーム画面の無操作アイドリング休止",
                    current = config.homeIdleMode,
                    items = ThreeJsHomeIdleMode.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(homeIdleMode = chosen))
                }
            }
        )

        sectionsContainer.addView(
            buildSettingCard(
                label = "OS省電力・低残量(20%以下)・発熱時の連動",
                valueText = config.systemEcoGuard.title,
                detailText = config.systemEcoGuard.detail
            ) {
                openEnumSelector(
                    dialogTitle = "OS省電力・低残量・発熱時の連動",
                    current = config.systemEcoGuard,
                    items = ThreeJsSystemEcoGuard.entries.map {
                        PowerChoiceItem(it, it.title, it.detail)
                    }
                ) { chosen ->
                    applyUpdatedConfig(config.copy(systemEcoGuard = chosen))
                }
            }
        )

        sectionsContainer.addView(buildSectionTitle("手動キャッシュ・メモリ解放"))
        sectionsContainer.addView(buildPurgeButton())
    }

    private fun buildStatusBanner(
        config: ThreeJsPowerConfig,
        effective: EffectiveThreeJsPowerPlan
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(BANNER_BG_COLOR)
                setStroke(dp(1), BANNER_STROKE_COLOR)
            }
        }

        val fpsSummary = if (effective.loopMode == LoopExecutionMode.SINGLE_FRAME_FREEZE) {
            "静止1フレーム凍結"
        } else {
            val approxFps = (MILLIS_PER_SECOND / effective.frameIntervalMs.coerceAtLeast(1L))
                .coerceAtLeast(1L)
            "実効上限 ${approxFps}FPS"
        }
        val headline = TextView(this).apply {
            text = "$fpsSummary · 長辺 ${effective.maxRenderEdgePx}px (DPR ${effective.maxPixelRatio}) · ${effective.gpuQuality.shortLabel}"
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ACCENT_GREEN_COLOR)
        }

        val osStatus = TextView(this).apply {
            text = ThreeJsPowerStore.formatSystemStatus(this@ThreeJsPowerSettingsActivity)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(SUB_TEXT_COLOR)
            setPadding(0, dp(4), 0, 0)
        }

        val note = TextView(this).apply {
            text = "現在の構成: ${ThreeJsPowerPolicy.formatSummary(config)}"
            textSize = 11.5f
            setTextColor(MUTED_TEXT_COLOR)
            setPadding(0, dp(4), 0, 0)
        }

        box.addView(headline)
        box.addView(osStatus)
        box.addView(note)
        return box
    }

    private fun buildSectionTitle(text: String): View {
        return TextView(this).apply {
            this.text = text
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(SECTION_HEADER_COLOR)
            setPadding(dp(2), dp(14), dp(2), dp(6))
        }
    }

    private fun buildPresetRow(activePreset: ThreeJsPowerPreset): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val presets = listOf(
            ThreeJsPowerPreset.BALANCED_ECO,
            ThreeJsPowerPreset.MAX_SAVER,
            ThreeJsPowerPreset.QUALITY
        )
        for (preset in presets) {
            val state = if (preset == activePreset) {
                ChipSelectionState.SELECTED
            } else {
                ChipSelectionState.UNSELECTED
            }
            wrapper.addView(buildPresetChip(preset, state))
        }
        return wrapper
    }

    private fun buildPresetChip(
        preset: ThreeJsPowerPreset,
        state: ChipSelectionState
    ): View {
        val bgColor = if (state == ChipSelectionState.SELECTED) {
            SELECTED_CARD_BG_COLOR
        } else {
            CARD_BG_COLOR
        }
        val strokeColor = if (state == ChipSelectionState.SELECTED) {
            ACCENT_PURPLE_COLOR
        } else {
            CARD_STROKE_COLOR
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(bgColor)
                setStroke(dp(1), strokeColor)
            }
            setOnClickListener {
                ThreeJsPowerStore.applyPreset(this@ThreeJsPowerSettingsActivity, preset)
                rebuildContent()
                Toast.makeText(
                    this@ThreeJsPowerSettingsActivity,
                    "「${preset.title}」を適用しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = preset.title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TITLE_TEXT_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val badgeView = TextView(this).apply {
            text = if (state == ChipSelectionState.SELECTED) {
                "● 適用中"
            } else {
                preset.badge
            }
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(
                if (state == ChipSelectionState.SELECTED) {
                    ACCENT_PURPLE_COLOR
                } else {
                    MUTED_TEXT_COLOR
                }
            )
        }
        titleRow.addView(titleView)
        titleRow.addView(badgeView)

        val subView = TextView(this).apply {
            text = preset.subtitle
            textSize = 12f
            setTextColor(SUB_TEXT_COLOR)
            setPadding(0, dp(3), 0, 0)
        }

        card.addView(titleRow)
        card.addView(subView)
        return card
    }

    private fun buildSettingCard(
        label: String,
        valueText: String,
        detailText: String,
        onTap: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(CARD_BG_COLOR)
                setStroke(dp(1), CARD_STROKE_COLOR)
            }
            setOnClickListener { onTap() }
        }

        val titleView = TextView(this).apply {
            text = label
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(TITLE_TEXT_COLOR)
        }

        val currentView = TextView(this).apply {
            text = valueText
            textSize = 13f
            setTextColor(ACCENT_PURPLE_COLOR)
            setPadding(0, dp(4), 0, 0)
        }

        val detailView = TextView(this).apply {
            text = detailText
            textSize = 11.5f
            setTextColor(MUTED_TEXT_COLOR)
            setPadding(0, dp(3), 0, 0)
        }

        card.addView(titleView)
        card.addView(currentView)
        card.addView(detailView)
        return card
    }

    private fun buildPurgeButton(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(PURGE_BG_COLOR)
                setStroke(dp(1), PURGE_STROKE_COLOR)
            }
            addView(
                TextView(this@ThreeJsPowerSettingsActivity).apply {
                    text = "three.js のキャッシュ・待機プロセスを今すぐ全破棄"
                    textSize = 13.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(PURGE_TITLE_COLOR)
                }
            )
            addView(
                TextView(this@ThreeJsPowerSettingsActivity).apply {
                    text = "WebViewキャッシュ・GPUコンテキスト・メモリ上の作品一覧キャッシュを即座に解放します"
                    textSize = 11.5f
                    setTextColor(SUB_TEXT_COLOR)
                    setPadding(0, dp(3), 0, 0)
                }
            )
            setOnClickListener {
                ThreeJsPowerStore.requestImmediatePurge(this@ThreeJsPowerSettingsActivity)
                Toast.makeText(
                    this@ThreeJsPowerSettingsActivity,
                    "three.js のメモリ・キャッシュを解放しました",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun <T> openEnumSelector(
        dialogTitle: String,
        current: T,
        items: List<PowerChoiceItem<T>>,
        onPicked: (T) -> Unit
    ) {
        val labels = items.map { item ->
            "${item.title}\n  ${item.detail}"
        }.toTypedArray()
        val checkedIndex = items.indexOfFirst { it.value == current }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                dialog.dismiss()
                if (which in items.indices) {
                    onPicked(items[which].value)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun applyUpdatedConfig(next: ThreeJsPowerConfig) {
        ThreeJsPowerStore.saveConfig(this, next)
        rebuildContent()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private val BG_ROOT_COLOR = Color.parseColor("#0B0B0D")
        private val BANNER_BG_COLOR = Color.parseColor("#111716")
        private val BANNER_STROKE_COLOR = Color.parseColor("#233A31")
        private val CARD_BG_COLOR = Color.parseColor("#13131A")
        private val SELECTED_CARD_BG_COLOR = Color.parseColor("#1B1729")
        private val CARD_STROKE_COLOR = Color.parseColor("#22222E")
        private val PURGE_BG_COLOR = Color.parseColor("#1C1317")
        private val PURGE_STROKE_COLOR = Color.parseColor("#472631")
        private val PURGE_TITLE_COLOR = Color.parseColor("#FFB4C0")
        private val TITLE_TEXT_COLOR = Color.parseColor("#ECECF4")
        private val SUB_TEXT_COLOR = Color.parseColor("#A8A8B8")
        private val MUTED_TEXT_COLOR = Color.parseColor("#7E7E8F")
        private val SECTION_HEADER_COLOR = Color.parseColor("#9A92B8")
        private val ACCENT_PURPLE_COLOR = Color.parseColor("#D0BCFF")
        private val ACCENT_GREEN_COLOR = Color.parseColor("#7DD3A8")
    }
}
