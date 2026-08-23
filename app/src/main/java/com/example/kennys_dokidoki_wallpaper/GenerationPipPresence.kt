package com.example.kennys_dokidoki_wallpaper

import java.util.concurrent.CopyOnWriteArrayList

/**
 * PiP画面の生存を、生成進捗のstateとは別に知らせる。
 * 閉じた瞬間にビルダーの再表示ボタンを出すため。
 */
object GenerationPipPresence {
    @Volatile
    var isActive: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        listeners.forEach { it.invoke(active) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
