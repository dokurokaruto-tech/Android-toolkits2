package com.example.kennys_dokidoki_wallpaper

/**
 * 返信待ちバブルの文言。「キャラ返信中」+ 点々アニメーション。
 * 点は 1→2→3→1… と繰り返す（0個は使わない）。
 */
internal object ChatPendingBubble {
    const val LABEL = "キャラ返信中"
    const val MAX_DOTS = 3
    const val DOT_INTERVAL_MS = 400L

    /** tick=0 で1個、以降 tick ごとに増え、MAX_DOTS を超えたら1個に戻る。 */
    fun text(tick: Int): String = LABEL + ".".repeat(tick % MAX_DOTS + 1)

    /** 本文が返信待ちの仮文言なら true。 */
    fun isPending(text: String): Boolean = text.startsWith(LABEL)
}
