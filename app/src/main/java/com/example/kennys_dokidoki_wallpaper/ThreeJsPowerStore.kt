package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

object ThreeJsPowerStore {
    const val PREFS_NAME = "settings"
    const val KEY_BG_MODE = "threejs_power_bg_mode"
    const val KEY_IDLE_PURGE = "threejs_power_idle_purge"
    const val KEY_SCREEN_OFF = "threejs_power_screen_off"
    const val KEY_FPS_CAP = "threejs_power_fps_cap"
    const val KEY_RES_TIER = "threejs_power_res_tier"
    const val KEY_GPU_QUALITY = "threejs_power_gpu_quality"
    const val KEY_HOME_IDLE = "threejs_power_home_idle"
    const val KEY_ECO_GUARD = "threejs_power_eco_guard"

    const val ACTION_POWER_CHANGED =
        "com.example.kennys_dokidoki_wallpaper.ACTION_THREEJS_POWER_CHANGED"
    const val ACTION_PURGE_NOW =
        "com.example.kennys_dokidoki_wallpaper.ACTION_THREEJS_PURGE_NOW"

    private const val PERCENT_SCALE = 100

    val ALL_PREF_KEYS: Set<String> = setOf(
        KEY_BG_MODE,
        KEY_IDLE_PURGE,
        KEY_SCREEN_OFF,
        KEY_FPS_CAP,
        KEY_RES_TIER,
        KEY_GPU_QUALITY,
        KEY_HOME_IDLE,
        KEY_ECO_GUARD
    )

    fun loadConfig(context: Context): ThreeJsPowerConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = ThreeJsPowerPolicy.defaultConfig()
        return ThreeJsPowerConfig(
            backgroundMode = ThreeJsBackgroundMode.fromStored(
                prefs.getString(KEY_BG_MODE, defaults.backgroundMode.name)
            ),
            idlePurgeDelay = ThreeJsIdlePurgeDelay.fromStored(
                prefs.getString(KEY_IDLE_PURGE, defaults.idlePurgeDelay.name)
            ),
            screenOffPolicy = ThreeJsScreenOffPolicy.fromStored(
                prefs.getString(KEY_SCREEN_OFF, defaults.screenOffPolicy.name)
            ),
            frameRateCap = ThreeJsFrameRateCap.fromStored(
                prefs.getString(KEY_FPS_CAP, defaults.frameRateCap.name)
            ),
            resolutionTier = ThreeJsResolutionTier.fromStored(
                prefs.getString(KEY_RES_TIER, defaults.resolutionTier.name)
            ),
            gpuQuality = ThreeJsGpuQuality.fromStored(
                prefs.getString(KEY_GPU_QUALITY, defaults.gpuQuality.name)
            ),
            homeIdleMode = ThreeJsHomeIdleMode.fromStored(
                prefs.getString(KEY_HOME_IDLE, defaults.homeIdleMode.name)
            ),
            systemEcoGuard = ThreeJsSystemEcoGuard.fromStored(
                prefs.getString(KEY_ECO_GUARD, defaults.systemEcoGuard.name)
            )
        )
    }

    fun saveConfig(context: Context, config: ThreeJsPowerConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BG_MODE, config.backgroundMode.name)
            .putString(KEY_IDLE_PURGE, config.idlePurgeDelay.name)
            .putString(KEY_SCREEN_OFF, config.screenOffPolicy.name)
            .putString(KEY_FPS_CAP, config.frameRateCap.name)
            .putString(KEY_RES_TIER, config.resolutionTier.name)
            .putString(KEY_GPU_QUALITY, config.gpuQuality.name)
            .putString(KEY_HOME_IDLE, config.homeIdleMode.name)
            .putString(KEY_ECO_GUARD, config.systemEcoGuard.name)
            .apply()
        notifyPowerChanged(context)
    }

    fun applyPreset(context: Context, preset: ThreeJsPowerPreset): ThreeJsPowerConfig {
        val next = ThreeJsPowerPolicy.presetConfig(preset)
        saveConfig(context, next)
        return next
    }

    fun readSystemStress(context: Context): SystemPowerStress {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            if (pm.isPowerSaveMode) {
                return SystemPowerStress.SAVER_OR_THERMAL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                    return SystemPowerStress.SAVER_OR_THERMAL
                }
            }
        }
        if (isUnpluggedLowBattery(context)) {
            return SystemPowerStress.SAVER_OR_THERMAL
        }
        return SystemPowerStress.NORMAL
    }

    fun formatSystemStatus(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val saverOn = pm?.isPowerSaveMode == true
        val thermalWarm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } else {
            false
        }
        val lowBat = isUnpluggedLowBattery(context)
        val saverLabel = if (saverOn) {
            "ON"
        } else {
            "OFF"
        }
        val thermalLabel = if (thermalWarm) {
            "高温検知"
        } else {
            "正常"
        }
        val batLabel = if (lowBat) {
            "20%以下"
        } else {
            "通常"
        }
        return "OS省電力: $saverLabel · 温度: $thermalLabel · 残量: $batLabel"
    }

    fun resolvePlan(
        context: Context,
        homeTouch: HomeTouchState
    ): EffectiveThreeJsPowerPlan {
        val config = loadConfig(context)
        val stress = readSystemStress(context)
        return ThreeJsPowerPolicy.resolveEffectivePlan(config, stress, homeTouch)
    }

    fun buildSummaryText(context: Context): String {
        return ThreeJsPowerPolicy.formatSummary(loadConfig(context))
    }

    fun notifyPowerChanged(context: Context) {
        val intent = Intent(ACTION_POWER_CHANGED).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    fun requestImmediatePurge(context: Context) {
        ThreeJsWorkStore.evictMemoryCache()
        val intent = Intent(ACTION_PURGE_NOW).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun isUnpluggedLowBattery(context: Context): Boolean {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false

        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (isCharging) {
            return false
        }

        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return false
        }
        val percent = (level * PERCENT_SCALE) / scale
        return percent <= ThreeJsPowerPolicy.LOW_BATTERY_PERCENT
    }
}
