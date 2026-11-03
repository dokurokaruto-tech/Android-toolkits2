package com.example.kennys_dokidoki_wallpaper

/** Selects which built-in content renderer the live wallpaper service uses. */
enum class WallpaperRenderMode(val preferenceValue: String) {
    IMAGE("image"),
    THREE_JS("three_js");

    companion object {
        const val PREFERENCES_NAME = "wallpaper_prefs"
        const val KEY = "wallpaper_render_mode"

        fun fromPreference(value: String?): WallpaperRenderMode =
            values().firstOrNull { it.preferenceValue == value } ?: IMAGE
    }
}
