package com.example.kennys_dokidoki_wallpaper

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context

/**
 * アプリ（＝プロセス）の起動処理を担う Application クラスじゃ。
 *
 * 壁紙の「チャット用 / ホーム画面用」切り替えは `is_chat_active` という設定値で行っている。
 * これが前回の起動でチャットを開いたままプロセスが落とされた場合などに `true` のまま残ると、
 * ホーム画面なのにチャット用のイメージセットが表示されてしまうバグになる。
 *
 * プロセスが新しく作られた時点ではチャットは絶対に開いていないので、
 * ここで必ず `false` に戻すことでバグを防ぐ。
 * （チャット画面を開いたときに onResume で改めて `true` にする）
 */
class KennysApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("is_chat_active", false)
            .apply()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // オリジナル画像はアプリを最小化するまでだけ保持する。
            OriginalImageMemoryCache.clear()
            com.bumptech.glide.Glide.get(this).clearMemory()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        OriginalImageMemoryCache.clear()
        com.bumptech.glide.Glide.get(this).clearMemory()
    }
}
