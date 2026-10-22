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

    data class Choice(val label: String, val color: Int)

    /** 背景色・文字色のどちらにも使える、色相ごとのプリセット。 */
    val presets = listOf(
        Choice("黒", 0xFF000000.toInt()),
        Choice("夜", 0xFF101116.toInt()),
        Choice("鉄", 0xFF292D32.toInt()),
        Choice("灰", 0xFF5F6368.toInt()),
        Choice("銀", 0xFFB0BEC5.toInt()),
        Choice("白", 0xFFF5F5F5.toInt()),
        Choice("深紅", 0xFF8B0000.toInt()),
        Choice("赤", 0xFFD32F2F.toInt()),
        Choice("鮮紅", 0xFFFF2020.toInt()),
        Choice("珊瑚", 0xFFFF6F61.toInt()),
        Choice("鮭", 0xFFFF8A80.toInt()),
        Choice("桃", 0xFFF48FB1.toInt()),
        Choice("茜", 0xFFA52A2A.toInt()),
        Choice("葡萄酒", 0xFF722F37.toInt()),
        Choice("焦茶", 0xFF4E342E.toInt()),
        Choice("橙", 0xFFEF6C00.toInt()),
        Choice("蜜柑", 0xFFFF9800.toInt()),
        Choice("琥珀", 0xFFFFB300.toInt()),
        Choice("山吹", 0xFFFFD54F.toInt()),
        Choice("黄", 0xFFFFEB3B.toInt()),
        Choice("檸檬", 0xFFFFF176.toInt()),
        Choice("クリーム", 0xFFFFF9C4.toInt()),
        Choice("苔", 0xFF556B2F.toInt()),
        Choice("オリーブ", 0xFF827717.toInt()),
        Choice("森", 0xFF1B5E20.toInt()),
        Choice("緑", 0xFF43A047.toInt()),
        Choice("若草", 0xFF8BC34A.toInt()),
        Choice("黄緑", 0xFF9CCC65.toInt()),
        Choice("ミント", 0xFF69F0AE.toInt()),
        Choice("青緑", 0xFF00897B.toInt()),
        Choice("青磁", 0xFF80CBC4.toInt()),
        Choice("碧", 0xFF00BCD4.toInt()),
        Choice("水色", 0xFF4DD0E1.toInt()),
        Choice("空色", 0xFF64B5F6.toInt()),
        Choice("青", 0xFF1E88E5.toInt()),
        Choice("紺", 0xFF0D47A1.toInt()),
        Choice("藍", 0xFF3949AB.toInt()),
        Choice("青紫", 0xFF5E35B1.toInt()),
        Choice("紫", 0xFF8E24AA.toInt()),
        Choice("藤", 0xFF9575CD.toInt()),
        Choice("薄紫", 0xFFCE93D8.toInt()),
        Choice("紅紫", 0xFFD81B60.toInt()),
        Choice("桜", 0xFFEFB7C5.toInt()),
        Choice("砂", 0xFFC2A57A.toInt()),
        Choice("ベージュ", 0xFFD7CCC8.toInt()),
        Choice("茶", 0xFF795548.toInt()),
        Choice("青灰", 0xFF607D8B.toInt()),
        Choice("蛍光黄", 0xFFFFFF00.toInt()),
        Choice("蛍光緑", 0xFF00FF66.toInt()),
        Choice("蛍光青", 0xFF00E5FF.toInt()),
        Choice("蛍光紫", 0xFF651FFF.toInt()),
        Choice("蛍光桃", 0xFFFF00FF.toInt()),
        Choice("夕焼け", 0xFFFF7043.toInt())
    )

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
