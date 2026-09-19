package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * サムネイル紐づけは二重に走る（監視中の seed と完了後の apply）。
 * 2回目は ALREADY になるが、それは失敗ではなく「もう載っている」。
 */
class ThumbnailBindPolicyTest {

    @Test
    fun alreadyBoundCountsAsBound() {
        assertTrue(ThumbnailBindPolicy.isBound(ThumbnailBindPolicy.BindOutcome.ALREADY))
        assertTrue(ThumbnailBindPolicy.isBound(ThumbnailBindPolicy.BindOutcome.CHANGED))
        assertTrue(ThumbnailBindPolicy.isBound(ThumbnailBindPolicy.BindOutcome.PENDING))
        assertFalse(ThumbnailBindPolicy.isBound(ThumbnailBindPolicy.BindOutcome.SKIPPED))
    }

    @Test
    fun boundCountIgnoresSkipped() {
        assertEquals(
            2,
            ThumbnailBindPolicy.boundCount(
                listOf(
                    ThumbnailBindPolicy.BindOutcome.ALREADY,
                    ThumbnailBindPolicy.BindOutcome.SKIPPED,
                    ThumbnailBindPolicy.BindOutcome.CHANGED
                )
            )
        )
    }

    @Test
    fun messageReportsSuccessEvenWhenNothingChanged() {
        assertEquals(
            "サムネイル 1 枚を紐づけた。",
            ThumbnailBindPolicy.completionMessage(
                listOf(ThumbnailBindPolicy.BindOutcome.ALREADY),
                null
            )
        )
    }

    @Test
    fun messageFallsBackToErrorThenDefault() {
        assertEquals(
            "PCが応答しない",
            ThumbnailBindPolicy.completionMessage(
                listOf(ThumbnailBindPolicy.BindOutcome.SKIPPED),
                "PCが応答しない"
            )
        )
        assertEquals(
            "サムネイルはできたが紐づけ先が無い。",
            ThumbnailBindPolicy.completionMessage(
                listOf(ThumbnailBindPolicy.BindOutcome.SKIPPED),
                "  "
            )
        )
    }
}
