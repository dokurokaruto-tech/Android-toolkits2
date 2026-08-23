package com.example.kennys_dokidoki_wallpaper

/**
 * ライブ壁紙の登録は、通常のアプリからは勝手に切り替えられない。
 * すでにこのアプリが壁紙なら何もしない。そうでなければ一度だけ尋ねる。
 */
object WallpaperSetupPolicy {
    const val PREFS_NAME = "settings"
    const val KEY_PROMPTED = "live_wallpaper_setup_prompted_v1"
    const val TITLE = "ライブ壁紙にしますか？"
    const val MESSAGE =
        "このアプリをホーム画面の壁紙として使うには、システムの壁紙設定で一度選ぶ必要がある。今設定する？"
    const val POSITIVE = "設定する"
    const val NEGATIVE = "あとで"

    fun shouldPrompt(alreadyActive: Boolean, alreadyPrompted: Boolean): Boolean =
        !alreadyActive && !alreadyPrompted

    fun isOurWallpaper(
        servicePackage: String?,
        serviceClass: String?,
        appPackage: String,
        expectedClass: String
    ): Boolean =
        !servicePackage.isNullOrBlank() &&
            !serviceClass.isNullOrBlank() &&
            servicePackage == appPackage &&
            serviceClass == expectedClass
}
