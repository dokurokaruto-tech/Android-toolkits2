package com.example.kennys_dokidoki_wallpaper

/**
 * 画像ファイルの一括削除結果を通知する文面の規則。
 *
 * 削除できなかったのに「0件削除しました」と出る紛らわしさを無くし、
 * 失敗した件はリストに残したことをはっきり伝える。
 */
object ImageDeleteReportPolicy {
    fun summary(success: Int, failed: Int): String = when {
        success > 0 && failed == 0 -> "${success}件のファイルを削除しました。"
        success == 0 && failed > 0 -> "ファイルを削除できませんでした。${failed}件はリストに残してあります。"
        success == 0 -> "削除するファイルがありませんでした。"
        else -> "${success}件を削除し、${failed}件は削除できませんでした。失敗した分はリストに残してあります。"
    }
}
