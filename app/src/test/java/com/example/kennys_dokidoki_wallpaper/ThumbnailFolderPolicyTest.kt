package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailFolderPolicyTest {

    private fun entry(name: String, bytes: Long = 1) = ThumbnailFolderPolicy.Entry(name, bytes)

    @Test
    fun newestFileComesFirst() {
        val sorted = ThumbnailFolderPolicy.sorted(
            listOf(
                entry("card_a_111.jpg").copy(lastModified = 1),
                entry("GEN_20260919_100000.jpg_2222").copy(lastModified = 2),
                entry("GEN_20260920_090000.jpg_3333").copy(lastModified = 3)
            )
        )
        assertEquals("GEN_20260920_090000.jpg_3333", sorted[0].name)
        assertEquals("GEN_20260919_100000.jpg_2222", sorted[1].name)
        assertEquals("card_a_111.jpg", sorted[2].name)
    }

    @Test
    fun kindLabelFollowsPrefix() {
        assertEquals("カード", ThumbnailFolderPolicy.kindLabel("card_x_1.jpg"))
        assertEquals("プリセット", ThumbnailFolderPolicy.kindLabel("preset_y_2.jpg"))
        assertEquals("閲覧", ThumbnailFolderPolicy.kindLabel("lib_lib_3.jpg"))
        assertEquals("その他", ThumbnailFolderPolicy.kindLabel("notes.txt"))
    }

    @Test
    fun displayNameExtractsOwnerOrSummarizesHash() {
        assertEquals("abc123", ThumbnailFolderPolicy.displayName("card_abc123_deadbeef.jpg"))
        assertEquals("myPreset", ThumbnailFolderPolicy.displayName("preset_myPreset_c0ffee.jpg"))
        assertEquals("閲覧キャッシュ #deadbee", ThumbnailFolderPolicy.displayName("lib_lib_deadbeefcafe.jpg"))
        assertEquals("weird", ThumbnailFolderPolicy.displayName("weird.jpg"))
    }

    @Test
    fun sizeLabelUsesBAndKbAndMb() {
        assertEquals("512B", ThumbnailFolderPolicy.sizeLabel(512))
        assertEquals("2KB", ThumbnailFolderPolicy.sizeLabel(2048))
        assertEquals("1.5MB", ThumbnailFolderPolicy.sizeLabel(1536L * 1024))
    }
}
