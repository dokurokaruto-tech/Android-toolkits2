package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
