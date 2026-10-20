package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBackupPolicyTest {

    @Test
    fun diaryPathsRequireARealIsoDateAndExactArchivePath() {
        assertEquals("2026-09-30", LibraryBackupPolicy.diaryDateOf("diary/2026-09-30.json"))
        assertNull(LibraryBackupPolicy.diaryDateOf("diary/2026-02-30.json"))
        assertNull(LibraryBackupPolicy.diaryDateOf("diary/2026-09-30.json/extra"))
        assertNull(LibraryBackupPolicy.diaryDateOf("../2026-09-30.json"))
    }

    @Test
    fun imagePathsRejectDirectoryTraversal() {
        assertEquals("photo_123.img", LibraryBackupPolicy.imageNameOf("images/photo_123.img"))
        assertNull(LibraryBackupPolicy.imageNameOf("images/../secret.img"))
        assertNull(LibraryBackupPolicy.imageNameOf("images/subdir/photo.img"))
    }

    @Test
    fun mergeOnlyReplacesMissingOrOlderLocalItems() {
        assertTrue(LibraryBackupPolicy.shouldImport(0L, 1L))
        assertTrue(LibraryBackupPolicy.shouldImport(100L, 101L))
        assertFalse(LibraryBackupPolicy.shouldImport(100L, 100L))
        assertFalse(LibraryBackupPolicy.shouldImport(101L, 100L))
    }

    @Test
    fun protectsABackupWhenALocalDataCategorySuddenlyBecomesEmpty() {
        val previous = LibraryBackupCounts(diaries = 2, essays = 3, wishLists = 1, images = 4)
        val current = LibraryBackupCounts(diaries = 0, essays = 3, wishLists = 1, images = 4)

        assertNotNull(LibraryBackupSafetyPolicy.overwriteWarning(previous, current))
        assertNull(LibraryBackupSafetyPolicy.overwriteWarning(previous, previous))
        assertNull(LibraryBackupSafetyPolicy.overwriteWarning(null, current))
    }

    @Test
    fun protectsAgainstLargeUnexpectedDropsInRecordsAndPhotos() {
        val previous = LibraryBackupCounts(diaries = 16, essays = 4, wishLists = 4, images = 20)
        val fewRecords = LibraryBackupCounts(diaries = 3, essays = 1, wishLists = 1, images = 20)
        val fewImages = LibraryBackupCounts(diaries = 16, essays = 4, wishLists = 4, images = 2)

        assertNotNull(LibraryBackupSafetyPolicy.overwriteWarning(previous, fewRecords))
        assertNotNull(LibraryBackupSafetyPolicy.overwriteWarning(previous, fewImages))
    }

    @Test
    fun detectsLargeContentLossEvenWhenFileCountsStayTheSame() {
        val previous = LibraryBackupCounts(diaries = 2, essays = 1, wishLists = 0, images = 2, payloadBytes = 100_000L)
        val current = LibraryBackupCounts(diaries = 2, essays = 1, wishLists = 0, images = 2, payloadBytes = 1_000L)

        assertNotNull(LibraryBackupSafetyPolicy.overwriteWarning(previous, current))
    }
}
