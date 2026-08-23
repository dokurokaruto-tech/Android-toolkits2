package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class GeneratedFolderPickerPolicyTest {
    @Test
    fun formatsIsoDateAndKeepsLocalSuffix() {
        assertEquals("2026年8月23日", GeneratedFolderPickerPolicy.formatFolderLabel("2026-08-23"))
        assertEquals("2026年8月23日 (端末)", GeneratedFolderPickerPolicy.formatFolderLabel("2026-08-23 (端末)"))
        assertEquals("Unknown", GeneratedFolderPickerPolicy.formatFolderLabel("Unknown"))
        assertEquals("12 枚", GeneratedFolderPickerPolicy.countLabel(12))
        assertEquals("閲覧", GeneratedFolderPickerPolicy.TITLE)
    }
}
