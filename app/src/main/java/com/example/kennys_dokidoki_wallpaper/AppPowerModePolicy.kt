package com.example.kennys_dokidoki_wallpaper

/**
 * 画面を開いていない間、プロセスは何も抱えない。
 * 表示中の Activity が0になったら SLEEPING。常駐の心拍・監視・キャッシュは
 * ここで全部静止と判断し、壁紙サービスとユーザーが明示的に始めた仕事だけ残す。
 */
object AppPowerModePolicy {
    enum class Mode { FOREGROUND, SLEEPING }

    /** 起動中の Activity 数から電源モードを決める。 */
    fun modeFor(startedActivities: Int): Mode =
        if (startedActivities > 0) Mode.FOREGROUND else Mode.SLEEPING

    /** 対にならないライフサイクルコールでも数が負にならないよう守る。 */
    fun countAfter(current: Int, delta: Int): Int = (current + delta).coerceAtLeast(0)

    fun shouldHeartbeat(mode: Mode): Boolean = mode == Mode.FOREGROUND

    fun shouldPollLibrary(mode: Mode): Boolean = mode == Mode.FOREGROUND

    fun discardsCaches(mode: Mode): Boolean = mode == Mode.SLEEPING
}
