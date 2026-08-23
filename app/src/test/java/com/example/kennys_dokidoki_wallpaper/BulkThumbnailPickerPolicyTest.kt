package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun filterKeepsSelectionOnHiddenRows() {
        val rows = listOf(
            BulkThumbnailPickerPolicy.entry("a", "A", null),
            BulkThumbnailPickerPolicy.entry("b", "B", "file://b")
        )
        assertEquals(listOf("a"), BulkThumbnailPickerPolicy.visible(rows, BulkThumbnailPickerPolicy.Filter.MISSING).map { it.id })
        assertEquals(listOf("b"), BulkThumbnailPickerPolicy.visible(rows, BulkThumbnailPickerPolicy.Filter.SET).map { it.id })
        val toggled = rows.map { if (it.id == "b") BulkThumbnailPickerPolicy.toggle(it) else it }
        assertEquals(listOf("a", "b"), BulkThumbnailPickerPolicy.selectedIds(toggled))
    }

    @Test
    fun summaryCountsSetAndPicked() {
        val rows = listOf(
            BulkThumbnailPickerPolicy.entry("a", "A", null),
            BulkThumbnailPickerPolicy.entry("b", "B", "file://b"),
            BulkThumbnailPickerPolicy.entry("c", "C", "file://c")
        )
        assertEquals(
            "全3件。設定済み 2件、未設定 1件。生成 1件。",
            BulkThumbnailPickerPolicy.summary(rows)
        )
    }
}
