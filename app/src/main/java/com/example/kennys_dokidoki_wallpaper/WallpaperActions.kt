package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * 壁紙エンジンへ届けるアプリ内ブロードキャスト。
 *
 *   MainActivity / ChatOverlayActivity ──sendBroadcast(setPackage)──▶ MyWallpaperService
 *
 * 常に setPackage() を付け、受信側は RECEIVER_NOT_EXPORTED で登録する。
 * 旧コードは 4 つのアクション文字列を 3 ファイルにコピーしていた。
 */
object WallpaperActions {
    private const val PREFIX = "com.example.kennys_dokidoki_wallpaper."

    const val SIMULATE_TAP = PREFIX + "ACTION_SIMULATE_TAP"
    const val EXECUTE_ACTION = PREFIX + "ACTION_EXECUTE_ACTION"
    const val SHOW_SET_NAME = PREFIX + "ACTION_SHOW_SET_NAME"
    const val WALLPAPER_CHANGED = PREFIX + "ACTION_WALLPAPER_CHANGED"

    const val EXTRA_TAP_COUNT = "tap_count"
    const val EXTRA_ACTION_STRING = "action_string"

    fun filter(): IntentFilter = IntentFilter().apply {
        addAction(SIMULATE_TAP)
        addAction(EXECUTE_ACTION)
        addAction(SHOW_SET_NAME)
        addAction(WALLPAPER_CHANGED)
    }

    fun send(context: Context, action: String, configure: (Intent.() -> Unit)? = null) {
        val intent = Intent(action).setPackage(context.packageName)
        configure?.invoke(intent)
        context.sendBroadcast(intent)
    }

    fun notifyWallpaperChanged(context: Context) = send(context, WALLPAPER_CHANGED)

    fun showSetName(context: Context) = send(context, SHOW_SET_NAME)

    fun execute(context: Context, actionString: String) = send(context, EXECUTE_ACTION) {
        putExtra(EXTRA_ACTION_STRING, actionString)
    }
}
