package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDeleteReportPolicyTest {
    @Test
    fun allSucceededReportsCount() {
        assertEquals("3件のファイルを削除しました。", ImageDeleteReportPolicy.summary(3, 0))
    }

    @Test
    fun allFailedNeverSaysZeroDeleted() {
        assertEquals(
            "ファイルを削除できませんでした。2件はリストに残してあります。",
            ImageDeleteReportPolicy.summary(0, 2)
        )
    }

    @Test
    fun partialFailureMentionsBothCounts() {
        assertEquals(
            "1件を削除し、2件は削除できませんでした。失敗した分はリストに残してあります。",
            ImageDeleteReportPolicy.summary(1, 2)
        )
    }

    @Test
    fun emptySelectionIsNeutral() {
        assertEquals("削除するファイルがありませんでした。", ImageDeleteReportPolicy.summary(0, 0))
    }

    @Test
    fun singleFailureDistinguishesUserDecline() {
        assertEquals(
            "削除の確認が取り消されました。画像はリストに残してあります。",
            ImageDeleteReportPolicy.singleFailure(true)
        )
        assertEquals(
            "この画像は削除できませんでした。端末のギャラリーアプリなどから削除してください。",
            ImageDeleteReportPolicy.singleFailure(false)
        )
    }
}
