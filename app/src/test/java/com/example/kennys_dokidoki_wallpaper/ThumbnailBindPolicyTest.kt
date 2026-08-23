package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailBindPolicyTest {
    @Test
    fun encodeAndDecodeKeepCardAndPresetTargets() {
        val encoded = ThumbnailBindPolicy.encodeTargets(
            listOf(
                ThumbnailBindPolicy.Target.card("card-a"),
                ThumbnailBindPolicy.Target.preset("preset-b"),
                ThumbnailBindPolicy.Target("nope", ""),
                ThumbnailBindPolicy.Target.card("  card-c  ")
            )
        )
        assertEquals(
            listOf(
                ThumbnailBindPolicy.Target.card("card-a"),
                ThumbnailBindPolicy.Target.preset("preset-b"),
                ThumbnailBindPolicy.Target.card("card-c")
            ),
            ThumbnailBindPolicy.decodeTargets(encoded)
        )
        assertTrue(ThumbnailBindPolicy.decodeTargets(null).isEmpty())
        assertNull(ThumbnailBindPolicy.parseTarget("other", "x"))
    }

    @Test
    fun pairUsesTaskIndexSoAFailedMiddleTaskDoesNotShiftTargets() {
        val targets = listOf(
            ThumbnailBindPolicy.Target.card("one"),
            ThumbnailBindPolicy.Target.card("two"),
            ThumbnailBindPolicy.Target.preset("three")
        )
        val paired = ThumbnailBindPolicy.pairUrls(
            listOf(
                "http://pc/api/v1/files/2026-08-23/THUMB_stamp_abcd_0001.png",
                "http://pc/api/v1/files/2026-08-23/THUMB_stamp_abcd_0003.png?token=x"
            ),
            targets
        )
        assertEquals(
            listOf(
                targets[0] to "http://pc/api/v1/files/2026-08-23/THUMB_stamp_abcd_0001.png",
                targets[2] to "http://pc/api/v1/files/2026-08-23/THUMB_stamp_abcd_0003.png?token=x"
            ),
            paired
        )
    }

    @Test
    fun pendingMapSurvivesEncode() {
        val encoded = ThumbnailBindPolicy.encodePending(
            mapOf(
                "card:draft-1" to "http://pc/a.png",
                " preset:p " to " ",
                "" to "http://pc/b.png"
            )
        )
        assertEquals(
            mapOf("card:draft-1" to "http://pc/a.png"),
            ThumbnailBindPolicy.decodePending(encoded)
        )
        assertEquals(
            "card:draft-1",
            ThumbnailBindPolicy.pendingKey(ThumbnailBindPolicy.Target.card("draft-1"))
        )
    }
}
