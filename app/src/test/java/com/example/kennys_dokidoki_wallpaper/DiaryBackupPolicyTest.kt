package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryBackupPolicyTest {

    @Test
    fun diaryPathsRequireARealIsoDateAndExactArchivePath() {
        assertEquals("2026-09-30", DiaryBackupPolicy.diaryDateOf("diary/2026-09-30.json"))
        assertNull(DiaryBackupPolicy.diaryDateOf("diary/2026-02-30.json"))
        assertNull(DiaryBackupPolicy.diaryDateOf("diary/2026-09-30.json/extra"))
        assertNull(DiaryBackupPolicy.diaryDateOf("../2026-09-30.json"))
    }

    @Test
    fun imagePathsRejectDirectoryTraversal() {
        assertEquals("photo_123.img", DiaryBackupPolicy.imageNameOf("images/photo_123.img"))
        assertNull(DiaryBackupPolicy.imageNameOf("images/../secret.img"))
        assertNull(DiaryBackupPolicy.imageNameOf("images/subdir/photo.img"))
    }

    @Test
    fun retiredEntriesAreRecognizedOnlyForSafeLegacyPaths() {
        assertTrue(DiaryBackupPolicy.isRetiredRecordPath("notes/old/record-123.json"))
        assertFalse(DiaryBackupPolicy.isRetiredRecordPath("notes/../record-123.json"))
        assertFalse(DiaryBackupPolicy.isRetiredRecordPath("notes/old/../record-123.json"))
        assertFalse(DiaryBackupPolicy.isRetiredRecordPath("notes/old/not-safe!.json"))
        assertFalse(DiaryBackupPolicy.isRetiredRecordPath("diary/record-123.json"))
    }

    @Test
    fun mergeOnlyReplacesMissingOrOlderLocalItems() {
        assertTrue(DiaryBackupPolicy.shouldImport(0L, 1L))
        assertTrue(DiaryBackupPolicy.shouldImport(100L, 101L))
        assertFalse(DiaryBackupPolicy.shouldImport(100L, 100L))
        assertFalse(DiaryBackupPolicy.shouldImport(101L, 100L))
    }

    @Test
    fun protectsADiaryBackupWhenLocalDataSuddenlyBecomesEmpty() {
        val previous = DiaryBackupCounts(diaries = 2, retiredRecords = 0, images = 4)
        val current = DiaryBackupCounts(diaries = 0, retiredRecords = 0, images = 4)

        assertNotNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, current))
        assertNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, previous))
        assertNull(DiaryBackupSafetyPolicy.overwriteWarning(null, current))
    }

    @Test
    fun protectsOlderBackupsThatContainUnsupportedLegacyRecords() {
        val previous = DiaryBackupCounts(diaries = 2, retiredRecords = 3, images = 4)
        val current = DiaryBackupCounts(diaries = 2, retiredRecords = 0, images = 4)

        assertNotNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, current))
    }

    @Test
    fun protectsAgainstLargeUnexpectedDropsInRecordsAndPhotos() {
        val previous = DiaryBackupCounts(diaries = 24, retiredRecords = 0, images = 20)
        val fewRecords = DiaryBackupCounts(diaries = 3, retiredRecords = 0, images = 20)
        val fewImages = DiaryBackupCounts(diaries = 24, retiredRecords = 0, images = 2)

        assertNotNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, fewRecords))
        assertNotNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, fewImages))
    }

    @Test
    fun detectsLargeContentLossEvenWhenFileCountsStayTheSame() {
        val previous = DiaryBackupCounts(diaries = 2, retiredRecords = 0, images = 2, payloadBytes = 100_000L)
        val current = DiaryBackupCounts(diaries = 2, retiredRecords = 0, images = 2, payloadBytes = 1_000L)

        assertNotNull(DiaryBackupSafetyPolicy.overwriteWarning(previous, current))
    }
}
