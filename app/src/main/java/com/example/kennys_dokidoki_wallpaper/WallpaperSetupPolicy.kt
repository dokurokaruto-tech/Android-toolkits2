package com.example.kennys_dokidoki_wallpaper

/**
 * ライブ壁紙の登録は、通常のアプリからは勝手に切り替えられない。
 * いまホームの壁紙がこのアプリでなければ、起動の最初の画面で尋ねる。
 * 以前聞いたかどうかは見ない。再インストールでデータが残っても同じ。
 */
object WallpaperSetupPolicy {
    const val TITLE = "ライブ壁紙にしますか？"
    const val MESSAGE =
        "このアプリをホーム画面の壁紙として使うには、システムの壁紙設定で一度選ぶ必要がある。今設定する？"
    const val POSITIVE = "設定する"
    const val NEGATIVE = "あとで"

    fun shouldPrompt(alreadyActive: Boolean, askedThisSession: Boolean = false): Boolean =
        !alreadyActive && !askedThisSession

    fun isOurWallpaper(
        servicePackage: String?,
        serviceClass: String?,
        appPackage: String,
        expectedClass: String
    ): Boolean {
        if (servicePackage.isNullOrBlank() || serviceClass.isNullOrBlank()) return false
        if (servicePackage != appPackage) return false
        val expectedSimple = expectedClass.substringAfterLast('.')
        return serviceClass == expectedClass ||
            serviceClass.endsWith(".$expectedSimple") ||
            serviceClass == expectedSimple
    }
}
