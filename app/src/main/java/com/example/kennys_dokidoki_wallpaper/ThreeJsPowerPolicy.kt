package com.example.kennys_dokidoki_wallpaper

enum class ThreeJsBackgroundMode(
    val title: String,
    val detail: String
) {
    PAUSE_KEEP_WARM(
        title = "一時停止（高速復帰）",
        detail = "描画ループを0FPSに止めフレームRAMを解放。WebGLとシーンは温存しホーム復帰を高速化"
    ),
    PAUSE_PURGE_SCENE(
        title = "一時停止＋シーン・VRAM解放",
        detail = "描画を停止し、ジオメトリ・テクスチャ・描画リストを即時解放（復帰時に作品を再読込）"
    ),
    DEEP_STOP_PURGE_ALL(
        title = "完全停止・全キャッシュ破棄",
        detail = "WebView・GPUコンテキスト・仮想ディスプレイ・キャッシュをすべて破棄し完全に動作停止"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsBackgroundMode {
            return entries.firstOrNull { it.name == raw } ?: PAUSE_KEEP_WARM
        }
    }
}

enum class ThreeJsIdlePurgeDelay(
    val delayMs: Long,
    val title: String,
    val detail: String
) {
    NEVER(
        delayMs = -1L,
        title = "自動破棄しない",
        detail = "背面の間は選択した待機状態を維持する"
    ),
    AFTER_30S(
        delayMs = 30_000L,
        title = "非表示 30秒後に完全破棄",
        detail = "他アプリを30秒開いた時点でWebView・GPU・キャッシュをすべて破棄"
    ),
    AFTER_2M(
        delayMs = 120_000L,
        title = "非表示 2分後に完全破棄",
        detail = "短いアプリ切替は即復帰し、2分以上離れたら全メモリ・プロセスを破棄"
    ),
    AFTER_5M(
        delayMs = 300_000L,
        title = "非表示 5分後に完全破棄",
        detail = "5分間ホームに戻らなかった場合に全メモリ・プロセスを破棄"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsIdlePurgeDelay {
            return entries.firstOrNull { it.name == raw } ?: AFTER_2M
        }
    }
}

enum class ThreeJsScreenOffPolicy(
    val title: String,
    val detail: String
) {
    IMMEDIATE_DEEP_STOP(
        title = "画面消灯で即座に完全停止・全破棄",
        detail = "スリープに入った瞬間にWebView・GPU・キャッシュをすべて解放し待機電力をゼロ化"
    ),
    FOLLOW_BACKGROUND(
        title = "背面動作設定に従う",
        detail = "画面消灯時も「他アプリが前面に出たとき」と同じ方式で待機する"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsScreenOffPolicy {
            return entries.firstOrNull { it.name == raw } ?: IMMEDIATE_DEEP_STOP
        }
    }
}

enum class ThreeJsFrameRateCap(
    val intervalMs: Long,
    val fpsLabel: String,
    val title: String,
    val detail: String
) {
    FPS_60(
        intervalMs = 16L,
        fpsLabel = "60 FPS",
        title = "60 FPS（なめらか）",
        detail = "90Hz/120Hz端末での過剰描画を防ぎつつ滑らかに再生"
    ),
    FPS_30(
        intervalMs = 33L,
        fpsLabel = "30 FPS",
        title = "30 FPS（標準省電力）",
        detail = "VSync常時起動を止めタイマー駆動に切替。GPU負荷を約50%削減"
    ),
    FPS_20(
        intervalMs = 50L,
        fpsLabel = "20 FPS",
        title = "20 FPS（強省電力）",
        detail = "動きの印象を保ったままGPU・ImageReader転送負荷を約67%削減"
    ),
    FPS_12(
        intervalMs = 83L,
        fpsLabel = "12 FPS",
        title = "12 FPS（超省電力）",
        detail = "最小限のフレーム更新でバッテリー消費を大幅に抑制"
    ),
    STATIC_1FPS(
        intervalMs = 1000L,
        fpsLabel = "1 FPS",
        title = "1 FPS（極限・1秒更新）",
        detail = "1秒に1回だけ再描画し、それ以外の時間はGPUを休止させる"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsFrameRateCap {
            return entries.firstOrNull { it.name == raw } ?: FPS_30
        }
    }
}

enum class ThreeJsResolutionTier(
    val maxEdgePx: Int,
    val maxDpr: Int,
    val shortLabel: String,
    val title: String,
    val detail: String
) {
    HIGH_1600(
        maxEdgePx = 1600,
        maxDpr = 2,
        shortLabel = "1600px",
        title = "高精細（長辺 1600px）",
        detail = "ディテール重視のフル解像度レンダリング"
    ),
    BALANCED_1280(
        maxEdgePx = 1280,
        maxDpr = 1,
        shortLabel = "1280px",
        title = "標準省電力（長辺 1280px・DPR 1固定）",
        detail = "見た目の鮮明さを維持しつつ内部ピクセル倍率を1倍に固定して負荷を削減"
    ),
    ECO_960(
        maxEdgePx = 960,
        maxDpr = 1,
        shortLabel = "960px",
        title = "強省電力（長辺 960px）",
        detail = "1600px比でフラグメントシェーダー処理ピクセル数を約64%削減"
    ),
    ULTRA_ECO_720(
        maxEdgePx = 720,
        maxDpr = 1,
        shortLabel = "720px",
        title = "極限省電力（長辺 720px）",
        detail = "1600px比でピクセル数を約80%削減しVRAM帯域と発熱を最小化"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsResolutionTier {
            return entries.firstOrNull { it.name == raw } ?: BALANCED_1280
        }
    }
}

enum class ThreeJsAntialiasState {
    ENABLED,
    DISABLED
}

enum class ThreeJsShadowPolicy(val jsToken: String) {
    PROJECT_DEFAULT("project"),
    BASIC_ONLY("basic"),
    FORCE_OFF("off")
}

enum class ThreeJsGpuQuality(
    val antialias: ThreeJsAntialiasState,
    val shadowPolicy: ThreeJsShadowPolicy,
    val powerPref: String,
    val shortLabel: String,
    val title: String,
    val detail: String
) {
    FULL(
        antialias = ThreeJsAntialiasState.ENABLED,
        shadowPolicy = ThreeJsShadowPolicy.PROJECT_DEFAULT,
        powerPref = "default",
        shortLabel = "フル品質",
        title = "フル品質（MSAA有効・影維持）",
        detail = "マルチサンプルAAと作品のシャドウ設定をそのまま描画"
    ),
    ECO_NO_MSAA(
        antialias = ThreeJsAntialiasState.DISABLED,
        shadowPolicy = ThreeJsShadowPolicy.BASIC_ONLY,
        powerPref = "low-power",
        shortLabel = "省電力GPU",
        title = "省電力（MSAA無効・軽量影・低電力GPU）",
        detail = "高コストなMSAAを止め、重たいソフトシャドウを軽量化し低電力GPUコアを優先"
    ),
    ULTRA_ECO_NO_SHADOW(
        antialias = ThreeJsAntialiasState.DISABLED,
        shadowPolicy = ThreeJsShadowPolicy.FORCE_OFF,
        powerPref = "low-power",
        shortLabel = "極限GPU",
        title = "極限省電力（MSAA無効・影完全オフ・低電力GPU）",
        detail = "シャドウマップ生成パス自体をスキップしシェーダー負荷を最小化"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsGpuQuality {
            return entries.firstOrNull { it.name == raw } ?: ECO_NO_MSAA
        }
    }
}

enum class ThreeJsHomeIdleMode(
    val timeoutMs: Long,
    val shortLabel: String,
    val title: String,
    val detail: String
) {
    KEEP_RUNNING(
        timeoutMs = -1L,
        shortLabel = "常時再生",
        title = "常時再生（無操作でも止めない）",
        detail = "ホーム画面を表示している間は設定FPSのまま再生し続ける"
    ),
    THROTTLE_15S(
        timeoutMs = 15_000L,
        shortLabel = "15秒減速",
        title = "無操作 15秒で 10FPS へ自動減速",
        detail = "ホーム画面で15秒間タッチがないと10FPSへ落とし、触れた瞬間に元へ戻す"
    ),
    FREEZE_20S(
        timeoutMs = 20_000L,
        shortLabel = "20秒凍結",
        title = "無操作 20秒で描画凍結（GPU 0%）",
        detail = "直前のフレームを表示したままWebGLを完全休止し、画面に触れた瞬間に再開"
    ),
    FREEZE_60S(
        timeoutMs = 60_000L,
        shortLabel = "60秒凍結",
        title = "無操作 60秒で描画凍結（GPU 0%）",
        detail = "1分間ホーム画面を放置したら静止フレーム表示へ移行し、タッチで即時再開"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsHomeIdleMode {
            return entries.firstOrNull { it.name == raw } ?: FREEZE_60S
        }
    }
}

enum class ThreeJsSystemEcoGuard(
    val title: String,
    val detail: String
) {
    OFF(
        title = "連動しない",
        detail = "OSの省電力モードやバッテリー残量に関わらず上記設定を維持"
    ),
    AUTO_THROTTLE(
        title = "自動制限（省電力モード・残量20%以下・発熱時）",
        detail = "OS省電力・低残量・端末発熱を検知すると自動で15FPS以下・960px・影オフ・背面解放へ制限"
    ),
    FREEZE_ON_STRESS(
        title = "静止画化＋背面完全破棄（最大保護）",
        detail = "OS省電力・低残量・発熱時は1フレーム描画後にWebGLを凍結し、背面では全キャッシュを破棄"
    );

    companion object {
        fun fromStored(raw: String?): ThreeJsSystemEcoGuard {
            return entries.firstOrNull { it.name == raw } ?: AUTO_THROTTLE
        }
    }
}

enum class ThreeJsPowerPreset(
    val badge: String,
    val title: String,
    val subtitle: String
) {
    QUALITY(
        badge = "QUALITY",
        title = "高画質・即復帰",
        subtitle = "60FPS · 1600px · 一時停止で高速復帰"
    ),
    BALANCED_ECO(
        badge = "ECO DEFAULT",
        title = "標準省電力（推奨）",
        subtitle = "30FPS · 1280px · 一時停止＋2分後に全破棄 · 60秒無操作凍結"
    ),
    MAX_SAVER(
        badge = "MAX SAVER",
        title = "徹底省電力（完全破棄）",
        subtitle = "20FPS · 960px · 他アプリ起動で即全キャッシュ破棄 · 20秒凍結"
    ),
    CUSTOM(
        badge = "CUSTOM",
        title = "カスタム構成",
        subtitle = "各項目を個別に調整中"
    )
}

enum class SystemPowerStress {
    NORMAL,
    SAVER_OR_THERMAL
}

enum class HomeTouchState {
    ACTIVE,
    IDLE_TIMEOUT
}

enum class LoopExecutionMode(val jsToken: String) {
    CONTINUOUS("continuous"),
    SINGLE_FRAME_FREEZE("single_frame")
}

data class ThreeJsPowerConfig(
    val backgroundMode: ThreeJsBackgroundMode = ThreeJsBackgroundMode.PAUSE_KEEP_WARM,
    val idlePurgeDelay: ThreeJsIdlePurgeDelay = ThreeJsIdlePurgeDelay.AFTER_2M,
    val screenOffPolicy: ThreeJsScreenOffPolicy = ThreeJsScreenOffPolicy.IMMEDIATE_DEEP_STOP,
    val frameRateCap: ThreeJsFrameRateCap = ThreeJsFrameRateCap.FPS_30,
    val resolutionTier: ThreeJsResolutionTier = ThreeJsResolutionTier.BALANCED_1280,
    val gpuQuality: ThreeJsGpuQuality = ThreeJsGpuQuality.ECO_NO_MSAA,
    val homeIdleMode: ThreeJsHomeIdleMode = ThreeJsHomeIdleMode.FREEZE_60S,
    val systemEcoGuard: ThreeJsSystemEcoGuard = ThreeJsSystemEcoGuard.AUTO_THROTTLE
)

data class EffectiveThreeJsPowerPlan(
    val backgroundMode: ThreeJsBackgroundMode,
    val idlePurgeDelayMs: Long,
    val screenOffPolicy: ThreeJsScreenOffPolicy,
    val frameIntervalMs: Long,
    val loopMode: LoopExecutionMode,
    val maxRenderEdgePx: Int,
    val maxPixelRatio: Int,
    val gpuQuality: ThreeJsGpuQuality,
    val homeIdleMode: ThreeJsHomeIdleMode
)

object ThreeJsPowerPolicy {
    const val STRESS_MIN_INTERVAL_MS = 66L
    const val HOME_IDLE_THROTTLE_MS = 100L
    const val DEEP_STOP_SETTLE_MS = 450L
    const val LOW_BATTERY_PERCENT = 20

    fun defaultConfig(): ThreeJsPowerConfig {
        return presetConfig(ThreeJsPowerPreset.BALANCED_ECO)
    }

    fun defaultPlan(): EffectiveThreeJsPowerPlan {
        return resolveEffectivePlan(
            config = defaultConfig(),
            systemStress = SystemPowerStress.NORMAL,
            homeTouch = HomeTouchState.ACTIVE
        )
    }

    fun presetConfig(preset: ThreeJsPowerPreset): ThreeJsPowerConfig {
        return when (preset) {
            ThreeJsPowerPreset.QUALITY -> ThreeJsPowerConfig(
                backgroundMode = ThreeJsBackgroundMode.PAUSE_KEEP_WARM,
                idlePurgeDelay = ThreeJsIdlePurgeDelay.AFTER_5M,
                screenOffPolicy = ThreeJsScreenOffPolicy.IMMEDIATE_DEEP_STOP,
                frameRateCap = ThreeJsFrameRateCap.FPS_60,
                resolutionTier = ThreeJsResolutionTier.HIGH_1600,
                gpuQuality = ThreeJsGpuQuality.FULL,
                homeIdleMode = ThreeJsHomeIdleMode.KEEP_RUNNING,
                systemEcoGuard = ThreeJsSystemEcoGuard.AUTO_THROTTLE
            )
            ThreeJsPowerPreset.BALANCED_ECO,
            ThreeJsPowerPreset.CUSTOM -> ThreeJsPowerConfig(
                backgroundMode = ThreeJsBackgroundMode.PAUSE_KEEP_WARM,
                idlePurgeDelay = ThreeJsIdlePurgeDelay.AFTER_2M,
                screenOffPolicy = ThreeJsScreenOffPolicy.IMMEDIATE_DEEP_STOP,
                frameRateCap = ThreeJsFrameRateCap.FPS_30,
                resolutionTier = ThreeJsResolutionTier.BALANCED_1280,
                gpuQuality = ThreeJsGpuQuality.ECO_NO_MSAA,
                homeIdleMode = ThreeJsHomeIdleMode.FREEZE_60S,
                systemEcoGuard = ThreeJsSystemEcoGuard.AUTO_THROTTLE
            )
            ThreeJsPowerPreset.MAX_SAVER -> ThreeJsPowerConfig(
                backgroundMode = ThreeJsBackgroundMode.DEEP_STOP_PURGE_ALL,
                idlePurgeDelay = ThreeJsIdlePurgeDelay.AFTER_30S,
                screenOffPolicy = ThreeJsScreenOffPolicy.IMMEDIATE_DEEP_STOP,
                frameRateCap = ThreeJsFrameRateCap.FPS_20,
                resolutionTier = ThreeJsResolutionTier.ECO_960,
                gpuQuality = ThreeJsGpuQuality.ULTRA_ECO_NO_SHADOW,
                homeIdleMode = ThreeJsHomeIdleMode.FREEZE_20S,
                systemEcoGuard = ThreeJsSystemEcoGuard.FREEZE_ON_STRESS
            )
        }
    }

    fun detectPreset(config: ThreeJsPowerConfig): ThreeJsPowerPreset {
        val candidates = listOf(
            ThreeJsPowerPreset.QUALITY,
            ThreeJsPowerPreset.BALANCED_ECO,
            ThreeJsPowerPreset.MAX_SAVER
        )
        for (candidate in candidates) {
            if (presetConfig(candidate) == config) {
                return candidate
            }
        }
        return ThreeJsPowerPreset.CUSTOM
    }

    fun resolveEffectivePlan(
        config: ThreeJsPowerConfig,
        systemStress: SystemPowerStress,
        homeTouch: HomeTouchState
    ): EffectiveThreeJsPowerPlan {
        var bgMode = config.backgroundMode
        var purgeDelayMs = config.idlePurgeDelay.delayMs
        var intervalMs = config.frameRateCap.intervalMs
        var loopMode = LoopExecutionMode.CONTINUOUS
        var maxEdgePx = config.resolutionTier.maxEdgePx
        var maxDpr = config.resolutionTier.maxDpr
        var gpu = config.gpuQuality

        if (systemStress == SystemPowerStress.SAVER_OR_THERMAL) {
            when (config.systemEcoGuard) {
                ThreeJsSystemEcoGuard.OFF -> Unit
                ThreeJsSystemEcoGuard.AUTO_THROTTLE -> {
                    intervalMs = maxOf(intervalMs, STRESS_MIN_INTERVAL_MS)
                    maxEdgePx = minOf(maxEdgePx, ThreeJsResolutionTier.ECO_960.maxEdgePx)
                    maxDpr = 1
                    gpu = ThreeJsGpuQuality.ULTRA_ECO_NO_SHADOW
                    if (bgMode == ThreeJsBackgroundMode.PAUSE_KEEP_WARM) {
                        bgMode = ThreeJsBackgroundMode.PAUSE_PURGE_SCENE
                    }
                    purgeDelayMs = if (purgeDelayMs < 0L) {
                        ThreeJsIdlePurgeDelay.AFTER_30S.delayMs
                    } else {
                        minOf(purgeDelayMs, ThreeJsIdlePurgeDelay.AFTER_30S.delayMs)
                    }
                }
                ThreeJsSystemEcoGuard.FREEZE_ON_STRESS -> {
                    loopMode = LoopExecutionMode.SINGLE_FRAME_FREEZE
                    maxEdgePx = minOf(maxEdgePx, ThreeJsResolutionTier.ECO_960.maxEdgePx)
                    maxDpr = 1
                    gpu = ThreeJsGpuQuality.ULTRA_ECO_NO_SHADOW
                    bgMode = ThreeJsBackgroundMode.DEEP_STOP_PURGE_ALL
                }
            }
        }

        if (homeTouch == HomeTouchState.IDLE_TIMEOUT) {
            when (config.homeIdleMode) {
                ThreeJsHomeIdleMode.KEEP_RUNNING -> Unit
                ThreeJsHomeIdleMode.THROTTLE_15S -> {
                    intervalMs = maxOf(intervalMs, HOME_IDLE_THROTTLE_MS)
                }
                ThreeJsHomeIdleMode.FREEZE_20S,
                ThreeJsHomeIdleMode.FREEZE_60S -> {
                    loopMode = LoopExecutionMode.SINGLE_FRAME_FREEZE
                }
            }
        }

        return EffectiveThreeJsPowerPlan(
            backgroundMode = bgMode,
            idlePurgeDelayMs = purgeDelayMs,
            screenOffPolicy = config.screenOffPolicy,
            frameIntervalMs = intervalMs,
            loopMode = loopMode,
            maxRenderEdgePx = maxEdgePx,
            maxPixelRatio = maxDpr,
            gpuQuality = gpu,
            homeIdleMode = config.homeIdleMode
        )
    }

    fun toJsProfileJson(plan: EffectiveThreeJsPowerPlan): String {
        val aaLiteral = if (plan.gpuQuality.antialias == ThreeJsAntialiasState.ENABLED) {
            "true"
        } else {
            "false"
        }
        return buildString {
            append('{')
            append("\"frameIntervalMs\":").append(plan.frameIntervalMs).append(',')
            append("\"loopMode\":\"").append(plan.loopMode.jsToken).append("\",")
            append("\"maxPixelRatio\":").append(plan.maxPixelRatio).append(',')
            append("\"antialias\":").append(aaLiteral).append(',')
            append("\"shadowMode\":\"").append(plan.gpuQuality.shadowPolicy.jsToken).append("\",")
            append("\"powerPref\":\"").append(plan.gpuQuality.powerPref).append('"')
            append('}')
        }
    }

    fun formatSummary(config: ThreeJsPowerConfig): String {
        val preset = detectPreset(config)
        val bgShort = when (config.backgroundMode) {
            ThreeJsBackgroundMode.PAUSE_KEEP_WARM -> "背面:一時停止"
            ThreeJsBackgroundMode.PAUSE_PURGE_SCENE -> "背面:シーン解放"
            ThreeJsBackgroundMode.DEEP_STOP_PURGE_ALL -> "背面:完全破棄"
        }
        return "${preset.title} · ${config.frameRateCap.fpsLabel} · " +
            "${config.resolutionTier.shortLabel} · $bgShort"
    }
}
