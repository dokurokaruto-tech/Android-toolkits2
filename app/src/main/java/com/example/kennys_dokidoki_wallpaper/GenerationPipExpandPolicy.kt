package com.example.kennys_dokidoki_wallpaper

import java.util.Calendar
import java.util.TimeZone

/**
 * PiP の拡大は「PiPを全画面にする」のではなく、
 * アプリを前面に出して生成中画像を大きく見せ、戻るとその日の閲覧フォルダへ行く。
 */
object GenerationPipExpandPolicy {
    const val EXTRA_AUTO_OPEN_DATE = "AUTO_OPEN_GENERATED_DATE"
    const val EXTRA_SHOW_LIVE_PREVIEW = "SHOW_LIVE_GENERATION_PREVIEW"
    const val EXTRA_ALLOW_EMPTY_FOLDER = "ALLOW_EMPTY_GENERATED_FOLDER"
    const val LABEL_STOP = "停止"
    const val LABEL_SKIP = "スキップ"
    const val LABEL_LIVE_PREVIEW = "全画面で見る"
    const val LABEL_RESTORE_PIP = "PiPに戻る"
    private const val SUPPRESS_PIP_MS = 8_000L

    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    @Volatile
    private var suppressPipRelaunchUntil = 0L

    fun markExpanding(nowMillis: Long = System.currentTimeMillis()) {
        suppressPipRelaunchUntil = nowMillis + SUPPRESS_PIP_MS
    }

    fun shouldSuppressPipRelaunch(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis < suppressPipRelaunchUntil

    fun todayDate(nowMillis: Long = System.currentTimeMillis(), zoneId: String = "Asia/Tokyo"): String {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId))
        calendar.timeInMillis = nowMillis
        return "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun shouldAutoOpen(date: String?): Boolean = !date.isNullOrBlank() && DATE.matches(date)

    /** 最大化後の操作列。PiP中はシステム側が3つまでなので、戻すボタンは全画面だけ。 */
    fun fullscreenControlLabels(): List<String> =
        listOf(LABEL_STOP, LABEL_SKIP, LABEL_LIVE_PREVIEW, LABEL_RESTORE_PIP)

    fun shouldOfferRestorePip(isInPictureInPictureMode: Boolean): Boolean =
        !isInPictureInPictureMode
}
