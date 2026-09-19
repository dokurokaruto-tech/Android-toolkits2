package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BulkThumbnailPickerPolicyTest {
    @Test
    fun missingThumbsAreSelectedByDefault() {
        val missing = BulkThumbnailPickerPolicy.entry("a", "ポーズA", null)
        val set = BulkThumbnailPickerPolicy.entry("b", "ポーズB", "content://thumb/1")
        assertTrue(missing.selected)
        assertFalse(set.selected)
        assertEquals(BulkThumbnailPickerPolicy.STATUS_MISSING, missing.status)
        assertEquals(BulkThumbnailPickerPolicy.STATUS_SET, set.status)
    }

    @Test
    fun brokenLocalFilesCountAsUnset() {
        val gone = BulkThumbnailPickerPolicy.entry("a", "A", "file:///no/such/thumb.jpg")
        assertTrue(gone.selected)
        assertEquals(BulkThumbnailPickerPolicy.STATUS_MISSING, gone.status)
        assertFalse(gone.hasThumbnail)
    }

    @Test
    fun existingValidImageCountsAsSet() {
        val file = File.createTempFile("thumb", ".jpg").also {
            it.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        }
        try {
            val set = BulkThumbnailPickerPolicy.entry("b", "B", "file://" + file.absolutePath)
            assertFalse(set.selected)
            assertEquals(BulkThumbnailPickerPolicy.STATUS_SET, set.status)
        } finally {
            file.delete()
        }
    }

    @Test
    fun filterKeepsSelectionOnHiddenRows() {
        val setFile = File.createTempFile("thumb", ".jpg").also {
            it.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        }
        try {
            val rows = listOf(
                BulkThumbnailPickerPolicy.entry("a", "A", null),
                BulkThumbnailPickerPolicy.entry("b", "B", "file://" + setFile.absolutePath)
            )
            assertEquals(
                listOf("a"),
                BulkThumbnailPickerPolicy.visible(rows, BulkThumbnailPickerPolicy.Filter.MISSING)
                    .map { it.id }
            )
            assertEquals(
                listOf("b"),
                BulkThumbnailPickerPolicy.visible(rows, BulkThumbnailPickerPolicy.Filter.SET)
                    .map { it.id }
            )
            val toggled = rows.map { if (it.id == "b") BulkThumbnailPickerPolicy.toggle(it) else it }
            assertEquals(listOf("a", "b"), BulkThumbnailPickerPolicy.selectedIds(toggled))
        } finally {
            setFile.delete()
        }
    }

    @Test
    fun summaryCountsSetAndPicked() {
        val first = File.createTempFile("thumb", ".jpg").also {
            it.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        }
        val second = File.createTempFile("thumb", ".jpg").also {
            it.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
            )
        }
        try {
            val rows = listOf(
                BulkThumbnailPickerPolicy.entry("a", "A", null),
                BulkThumbnailPickerPolicy.entry("b", "B", "file://" + first.absolutePath),
                BulkThumbnailPickerPolicy.entry("c", "C", "file://" + second.absolutePath)
            )
            assertEquals(
                "全3件。設定済み 2件、未設定 1件。生成 1件。",
                BulkThumbnailPickerPolicy.summary(rows)
            )
        } finally {
            first.delete()
            second.delete()
        }
    }
}
