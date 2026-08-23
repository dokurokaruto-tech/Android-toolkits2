package com.example.kennys_dokidoki_wallpaper

/**
 * プロンプトビルダー下部の選択カード一覧の開閉を、次回起動まで覚える。
 */
object BuilderStripStatePolicy {
    const val PREFS_NAME = "settings"
    const val KEY_COLLAPSED = "builder_selected_strip_collapsed"

    fun isCollapsed(stored: Boolean?): Boolean = stored == true

    /** 畳んだあとも履歴ボタンとグラバーだけ残す。カード本体は隠す。 */
    fun peekHeight(headerHeight: Int, fallback: Int): Int =
        if (headerHeight > 0) headerHeight else fallback.coerceAtLeast(1)
}
