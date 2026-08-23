package com.example.kennys_dokidoki_wallpaper

/**
 * プロンプトビルダー下部の選択カード一覧の開閉を、次回起動まで覚える。
 */
object BuilderStripStatePolicy {
    const val PREFS_NAME = "settings"
    const val KEY_COLLAPSED = "builder_selected_strip_collapsed"

    fun isCollapsed(stored: Boolean?): Boolean = stored == true

    /** 畳んでもグラバー／履歴の下に、カード上端が少し見える高さ。 */
    fun peekHeight(headerHeight: Int, cardPeek: Int, fallbackHeader: Int): Int {
        val header = if (headerHeight > 0) headerHeight else fallbackHeader.coerceAtLeast(1)
        return header + cardPeek.coerceAtLeast(0)
    }

    fun cardPeek(hasCards: Boolean, peekWhenVisible: Int): Int =
        if (hasCards) peekWhenVisible.coerceAtLeast(0) else 0
}
