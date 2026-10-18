package com.example.kennys_dokidoki_wallpaper

import android.app.Application
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
        OriginalImageMemoryCache.initialize(this)
        ImageMemoryGovernor.start(this)
        // 開いていない間は心拍・監視・RAM画像を全て捨てる省電力思想の入り口。
        AppPowerGovernor.install(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ImageMemoryGovernor.onTrimMemory(this, level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ImageMemoryGovernor.onLowMemory(this)
    }
}
