package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThumbnailLocalCachePolicyTest {
    @Test
    fun remoteCardUrlsNeedALocalCopy() {
        assertTrue(
            ThumbnailLocalCachePolicy.needsLocalCopy(
                "http://pc:3001/api/v1/thumbnail-files/2026-08-23/THUMB_one.png?token=x"
            )
        )
        assertFalse(ThumbnailLocalCachePolicy.needsLocalCopy("file:///data/card_thumbnails/card_a_1.jpg"))
        assertFalse(ThumbnailLocalCachePolicy.needsLocalCopy("content://media/external/images/1"))
        assertFalse(ThumbnailLocalCachePolicy.needsLocalCopy(null))
    }

    @Test
    fun prefersCompressedMobileThumbnailThenOriginal() {
        val original = "http://192.168.1.45:3001/api/v1/thumbnail-files/2026-08-23/THUMB%20one.png?token=secret"
        assertEquals(
            listOf(
                "http://192.168.1.45:3001/api/v1/mobile-thumbnails/2026-08-23/THUMB%20one.png?token=secret",
                original
            ),
            ThumbnailLocalCachePolicy.downloadUrls(original)
        )
        val alreadyMobile = "http://pc/api/v1/mobile-thumbnails/2026-08-23/THUMB_one.png"
        assertEquals(
            listOf(alreadyMobile),
            ThumbnailLocalCachePolicy.downloadUrls(alreadyMobile)
        )
    }

    @Test
    fun keepsLocalCopyOfTheSameRemoteSource() {
        val remote = "http://pc/api/v1/files/2026-08-23/THUMB_one.png?token=old"
        val sameRemote = "http://192.168.1.45:3001/api/v1/thumbnail-files/2026-08-23/THUMB_one.png?token=new"
        val fileName = ThumbnailLocalCachePolicy.localFileName("card", "draft-1", remote)
        val local = "file:///data/user/0/app/files/card_thumbnails/$fileName"
        assertEquals(
            ThumbnailLocalCachePolicy.sourceKey(remote),
            ThumbnailLocalCachePolicy.sourceKey(sameRemote)
        )
        assertTrue(ThumbnailLocalCachePolicy.shouldKeepCurrent(remote, sameRemote))
        assertTrue(
            ThumbnailLocalCachePolicy.shouldKeepCurrent(
                "http://pc/api/v1/mobile-thumbnails/2026-08-23/THUMB_one.png",
                sameRemote
            )
        )
        assertTrue(ThumbnailLocalCachePolicy.shouldKeepCurrent(local, sameRemote))
        assertFalse(
            ThumbnailLocalCachePolicy.shouldKeepCurrent(
                local,
                "http://pc/api/v1/files/2026-08-23/THUMB_two.png"
            )
        )
        assertTrue(ThumbnailLocalCachePolicy.isManagedFileName(fileName))
        assertEquals("card_draft-1_", ThumbnailLocalCachePolicy.managedPrefix("card", "draft-1"))
    }

    @Test
    fun scalesToMobileThumbnailBounds() {
        assertEquals(480 to 854, ThumbnailLocalCachePolicy.scaledSize(1080, 1920))
        assertEquals(480 to 480, ThumbnailLocalCachePolicy.scaledSize(512, 512))
        assertEquals(200 to 300, ThumbnailLocalCachePolicy.scaledSize(200, 300))
        assertTrue(ThumbnailLocalCachePolicy.shouldRecompress(1080, 1920, alreadyJpeg = true))
        assertFalse(ThumbnailLocalCachePolicy.shouldRecompress(480, 854, alreadyJpeg = true))
        assertTrue(ThumbnailLocalCachePolicy.shouldRecompress(200, 300, alreadyJpeg = false))
        assertTrue(ThumbnailLocalCachePolicy.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertFalse(ThumbnailLocalCachePolicy.isJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E)))
    }

    @Test
    fun adoptsOnlyWhenCurrentUriIsStillRemote() {
        val local = "file:///data/card_thumbnails/card_a_1.jpg"
        assertTrue(
            ThumbnailLocalCachePolicy.shouldAdoptLocal(
                "http://pc/api/v1/mobile-thumbnails/2026-08-23/a.png",
                local
            )
        )
        assertFalse(ThumbnailLocalCachePolicy.shouldAdoptLocal(local, local))
        assertFalse(ThumbnailLocalCachePolicy.shouldAdoptLocal(null, local))
        assertFalse(ThumbnailLocalCachePolicy.shouldAdoptLocal("http://pc/a.png", null))
        assertEquals(2, ThumbnailLocalCachePolicy.MAX_IN_FLIGHT)
        assertEquals(2, ThumbnailLocalCachePolicy.decodeSampleSize(1920, 3414))
        assertEquals(4, ThumbnailLocalCachePolicy.decodeSampleSize(3840, 6828))
        assertEquals(1, ThumbnailLocalCachePolicy.decodeSampleSize(480, 854))
    }

    @Test
    fun collectsOnlyRemoteCardAndPresetUris() {
        val pending = ThumbnailLocalCachePolicy.collectPending(
            listOf(
                "card-a" to "http://pc/api/v1/thumbnail-files/2026-08-23/a.png",
                "card-b" to "file:///local/b.jpg",
                "card-c" to null
            ),
            listOf("preset-a" to "https://pc/api/v1/files/2026-08-23/p.png")
        )
        assertEquals(
            listOf(
                ThumbnailBindPolicy.Target.card("card-a") to
                    "http://pc/api/v1/thumbnail-files/2026-08-23/a.png",
                ThumbnailBindPolicy.Target.preset("preset-a") to
                    "https://pc/api/v1/files/2026-08-23/p.png"
            ),
            pending
        )
        assertNull(ThumbnailLocalCachePolicy.remoteRef("http://pc/api/v1/jobs/abc"))
    }

    @Test
    fun flagsOnlyMissingLocalFilesForRecovery() {
        val existing = File.createTempFile("thumb", ".jpg")
        try {
            assertFalse(
                ThumbnailLocalCachePolicy.needsRecovery("file://" + existing.absolutePath)
            )
        } finally {
            existing.delete()
        }
        assertTrue(
            ThumbnailLocalCachePolicy.needsRecovery("file:///data/app/card_thumbnails/card_a_1.jpg")
        )
        assertFalse(
            ThumbnailLocalCachePolicy.needsRecovery(
                "http://pc/api/v1/mobile-thumbnails/2026-08-23/a.png"
            )
        )
        assertFalse(ThumbnailLocalCachePolicy.needsRecovery(null))
        assertFalse(ThumbnailLocalCachePolicy.needsRecovery(""))
        assertFalse(ThumbnailLocalCachePolicy.needsRecovery("content://media/external/images/1"))
    }

    @Test
    fun diskCycleDropsOrphansThenOldestWhenOverBudget() {
        val keep = ThumbnailLocalCachePolicy.keepPrefixes(listOf("alive"), listOf("p1"))
        assertTrue(keep.contains("card_alive_"))
        assertTrue(keep.contains("preset_p1_"))
        val files = listOf(
            ThumbnailLocalCachePolicy.DiskFile("card_dead_aaa.jpg", 40, 1),
            ThumbnailLocalCachePolicy.DiskFile("card_alive_bbb.jpg", 40, 2),
            ThumbnailLocalCachePolicy.DiskFile("card_alive_ccc.jpg", 40, 3),
            ThumbnailLocalCachePolicy.DiskFile("notes.txt", 10, 1)
        )
        val deleted = ThumbnailLocalCachePolicy.filesToDelete(files, keep, maxBytes = 50)
        assertTrue(deleted.contains("card_dead_aaa.jpg"))
        assertTrue(deleted.contains("card_alive_bbb.jpg"))
        assertFalse(deleted.contains("card_alive_ccc.jpg"))
        assertFalse(deleted.contains("notes.txt"))
        assertTrue(
            ThumbnailLocalCachePolicy.filesToDelete(files, emptySet(), maxBytes = 10_000)
                .isEmpty()
        )
    }

    @Test
    fun libraryNameIsStableAcrossDeliveryPaths() {
        val mobile = "http://pc:3001/api/v1/mobile-thumbnails/2026-08-23/GEN_one.png?token=a"
        val original = "http://pc:3001/api/v1/files/2026-08-23/GEN_one.png?token=b"
        val name = ThumbnailLocalCachePolicy.libraryFileName(mobile)
        assertEquals(name, ThumbnailLocalCachePolicy.libraryFileName(original))
        assertTrue(ThumbnailLocalCachePolicy.isManagedFileName(name))
        assertEquals("lib", name?.substringBefore('_'))
        assertNull(ThumbnailLocalCachePolicy.libraryFileName("file:///local/a.jpg"))
    }

    @Test
    fun libraryFilesAreNeverOrphansAndEvictOldestFirst() {
        val keep = ThumbnailLocalCachePolicy.keepPrefixes(listOf("alive"), emptyList())
        val files = listOf(
            ThumbnailLocalCachePolicy.DiskFile("lib_2026-08-22_a.png_1111.jpg", 40, 1),
            ThumbnailLocalCachePolicy.DiskFile("lib_2026-08-23_b.png_2222.jpg", 40, 5),
            ThumbnailLocalCachePolicy.DiskFile("card_alive_aaa.jpg", 40, 3),
            ThumbnailLocalCachePolicy.DiskFile("card_dead_aaa.jpg", 40, 2)
        )
        // 予算内なら閲覧キャッシュは誰に紐づかなくても消えない。
        val kept = ThumbnailLocalCachePolicy.filesToDelete(files, keep, maxBytes = 10_000)
        assertFalse(kept.contains("lib_2026-08-22_a.png_1111.jpg"))
        assertFalse(kept.contains("lib_2026-08-23_b.png_2222.jpg"))
        assertTrue(kept.contains("card_dead_aaa.jpg"))

        // 予算超過では一番古い閲覧キャッシュから落ちる。
        val over = ThumbnailLocalCachePolicy.filesToDelete(files, keep, maxBytes = 50)
        assertTrue(over.contains("lib_2026-08-22_a.png_1111.jpg"))
        assertFalse(over.contains("lib_2026-08-23_b.png_2222.jpg"))
    }
}
