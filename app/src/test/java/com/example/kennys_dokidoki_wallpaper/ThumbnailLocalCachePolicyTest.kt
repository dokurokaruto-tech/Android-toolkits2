package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
