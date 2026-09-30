package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryTest {

    @Test
    fun entryRoundTripsThroughJson() {
        val entry = DiaryEntry(
            date = "2026-09-30",
            text = "雨。\n夜に散歩した。",
            photos = listOf(DiaryPhoto("a.img", 0.25f, 0.5f, 0.4f))
        )
        val decoded = DiaryJson.decode(entry.date, DiaryJson.encode(entry))
        assertEquals(entry.text, decoded.text)
        assertEquals(1, decoded.photos.size)
        assertEquals(0.25f, decoded.photos[0].xRatio, 0.0001f)
        assertEquals(0.4f, decoded.photos[0].widthRatio, 0.0001f)
    }

    @Test
    fun brokenJsonBecomesEmptyEntry() {
        assertTrue(DiaryJson.decode("2026-09-30", "{oops").isEmpty())
        assertTrue(DiaryJson.decode("2026-09-30", null).isEmpty())
    }

    @Test
    fun dateKeyIsZeroPadded() {
        assertEquals("2026-01-05", DiaryStore.dateKey(2026, 1, 5))
        assertEquals("2026年1月5日", DiaryStore.displayDate("2026-01-05"))
        assertEquals("こわれた", DiaryStore.displayDate("こわれた"))
    }

    @Test
    fun photoStaysInsideThePage() {
        assertEquals(0.6f, DiaryLayoutPolicy.clampPosition(0.9f, 0.4f), 0.0001f)
        assertEquals(0f, DiaryLayoutPolicy.clampPosition(-0.3f, 0.4f), 0.0001f)
    }

    @Test
    fun resizeIsBounded() {
        assertEquals(DiaryLayoutPolicy.MAX_WIDTH_RATIO, DiaryLayoutPolicy.resize(0.9f, 4f), 0.0001f)
        assertEquals(DiaryLayoutPolicy.MIN_WIDTH_RATIO, DiaryLayoutPolicy.resize(0.2f, 0.01f), 0.0001f)
    }

    @Test
    fun ratioConversionSurvivesZeroCanvas() {
        assertEquals(0f, DiaryLayoutPolicy.toRatio(100f, 0), 0.0001f)
        assertEquals(50, DiaryLayoutPolicy.toPx(0.5f, 100))
    }
}
