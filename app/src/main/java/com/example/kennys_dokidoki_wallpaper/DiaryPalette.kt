package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.Color
import kotlin.math.roundToInt

/** 日記ページと編集画面で共有する、わざと素朴な個人サイト風の配色。 */
internal data class DiaryPalette(
    val background: Int,
    val text: Int
) {
    val mutedText: Int get() = blend(background, text, 0.72f)
    val border: Int get() = blend(background, text, 0.48f)

    fun cellColor(level: Int): Int = when (level.coerceIn(0, 4)) {
        0 -> blend(background, text, 0.12f)
        1 -> blend(background, text, 0.36f)
        2 -> blend(background, text, 0.55f)
        3 -> blend(background, text, 0.76f)
        else -> blend(background, text, 0.96f)
    }

    companion object {
        fun blend(background: Int, foreground: Int, amount: Float): Int {
            val ratio = amount.coerceIn(0f, 1f)
            val reverse = 1f - ratio
            return Color.rgb(
                (Color.red(background) * reverse + Color.red(foreground) * ratio).roundToInt(),
                (Color.green(background) * reverse + Color.green(foreground) * ratio).roundToInt(),
                (Color.blue(background) * reverse + Color.blue(foreground) * ratio).roundToInt()
            )
        }
    }
}

internal object DiaryPaletteStore {

    private const val PREFS_NAME = "diary_page_appearance"
    private const val KEY_BACKGROUND = "background_color"
    private const val KEY_TEXT = "text_color"
    private val defaultPalette = DiaryPalette(0xFF000000.toInt(), 0xFFFF2020.toInt())

    fun load(context: Context): DiaryPalette {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DiaryPalette(
            preferences.getInt(KEY_BACKGROUND, defaultPalette.background),
            preferences.getInt(KEY_TEXT, defaultPalette.text)
        )
    }

    fun saveBackground(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BACKGROUND, color)
            .apply()
    }

    fun saveText(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TEXT, color)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BACKGROUND)
            .remove(KEY_TEXT)
            .apply()
    }
}
