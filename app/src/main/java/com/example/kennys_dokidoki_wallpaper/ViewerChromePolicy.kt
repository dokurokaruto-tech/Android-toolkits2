package com.example.kennys_dokidoki_wallpaper

/** 閲覧の全画面で、操作が止まったあとに仮チャットボタンなどを隠すまでの時間。 */
object ViewerChromePolicy {
    const val HIDE_AFTER_MS = 3000L

    fun shouldHide(nowMs: Long, lastInteractionMs: Long): Boolean {
        if (lastInteractionMs <= 0L) return false
        return nowMs - lastInteractionMs >= HIDE_AFTER_MS
    }
}
