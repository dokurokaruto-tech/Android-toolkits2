package com.example.kennys_dokidoki_wallpaper

/**
 * プロンプトビルダー下部の選択カード一覧の開閉を、次回起動まで覚える。
 */
object BuilderStripStatePolicy {
    const val PREFS_NAME = "settings"
    const val KEY_COLLAPSED = "builder_selected_strip_collapsed"

    fun isCollapsed(stored: Boolean?): Boolean = stored == true
}
