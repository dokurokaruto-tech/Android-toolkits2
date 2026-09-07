package com.example.kennys_dokidoki_wallpaper

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions

/**
 * KennysApplication.onCreate() から 1 行で呼ぶ起動処理。
 *
 *   override fun onCreate() {
 *       super.onCreate()
 *       AppBootstrap.init(this)
 *   }
 */
object AppBootstrap {
    fun init(app: Application) {
        AppSecrets.migrateLegacy(app)
        ThemeSetup.apply(app)
    }
}

/**
 * 秘密情報の読み書き。平文 "settings" に触るのはここの移行処理だけ。
 */
object AppSecrets {
    fun xaiApiKey(context: Context): String? = SecurePrefs.getString(context, PrefKeys.SECRET_XAI_API_KEY)

    fun setXaiApiKey(context: Context, value: String?) {
        SecurePrefs.putString(context, PrefKeys.SECRET_XAI_API_KEY, value?.trim())
    }

    fun agentApiKey(context: Context): String? = SecurePrefs.getString(context, PrefKeys.SECRET_AGENT_API_KEY)

    fun setAgentApiKey(context: Context, value: String?) {
        SecurePrefs.putString(context, PrefKeys.SECRET_AGENT_API_KEY, value?.trim())
    }

    /** 旧バージョンが settings.xml に平文保存していたキーを取り込む (冪等) */
    fun migrateLegacy(context: Context) {
        SecurePrefs.migrateFromPlain(context, PrefFiles.SETTINGS, PrefKeys.SECRET_XAI_API_KEY)
        SecurePrefs.migrateFromPlain(context, PrefFiles.SETTINGS, PrefKeys.SECRET_AGENT_API_KEY)
        // OpenRouter は OpenRouterManager.getApiKeys() 初回呼び出しで移行される
        OpenRouterManager.getApiKeys(context)
    }
}

/**
 * Material You (端末の壁紙由来のダイナミックカラー) とテーマモード。
 *
 *   設定 > 外観 > 「壁紙の色を使う」 … PrefKeys.DYNAMIC_COLOR (既定 ON)
 *   設定 > 外観 > テーマ            … system / light / dark
 *
 * 壁紙アプリなので、端末側の Material You と配色が揃うことは体験上重要。
 */
object ThemeSetup {
    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"

    fun apply(app: Application) {
        applyThemeMode(app)

        val options = DynamicColorsOptions.Builder()
            .setPrecondition { _, _ -> isDynamicColorEnabled(app) }
            .build()
        DynamicColors.applyToActivitiesIfAvailable(app, options)
    }

    fun isDynamicColorEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
            .getBoolean(PrefKeys.DYNAMIC_COLOR, true)
    }

    fun setDynamicColorEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
            .edit().putBoolean(PrefKeys.DYNAMIC_COLOR, enabled).apply()
    }

    fun themeMode(context: Context): String {
        return context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
            .getString(PrefKeys.THEME_MODE, MODE_SYSTEM) ?: MODE_SYSTEM
    }

    fun setThemeMode(context: Context, mode: String) {
        context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
            .edit().putString(PrefKeys.THEME_MODE, mode).apply()
        applyThemeMode(context)
    }

    private fun applyThemeMode(context: Context) {
        val mode = when (themeMode(context)) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
