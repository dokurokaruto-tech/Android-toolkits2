package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThumbnailStoragePolicyTest {

    private val internal = File("/data/internal/card_thumbnails")
    private val emulated = ThumbnailStoragePolicy.Candidate(File("/storage/emulated/0/app/files/thumbs"), false)
    private val sd = ThumbnailStoragePolicy.Candidate(File("/storage/0000-1111/Android/data/app/files/thumbs"), true)

    @Test
    fun internalPreferenceNeverLeavesInternalStorage() {
        val dir = ThumbnailStoragePolicy.resolveDir(
            ThumbnailStoragePolicy.Location.INTERNAL,
            internal,
            listOf(emulated, sd)
        )
        assertEquals(internal, dir)
    }

    @Test
    fun sdPreferencePicksFirstRemovableDir() {
        val dir = ThumbnailStoragePolicy.resolveDir(
            ThumbnailStoragePolicy.Location.SD_CARD,
            internal,
            listOf(emulated, sd)
        )
        assertEquals(sd.file, dir)
    }

    @Test
    fun sdPreferenceFallsBackToInternalWhenNoCard() {
        val dir = ThumbnailStoragePolicy.resolveDir(
            ThumbnailStoragePolicy.Location.SD_CARD,
            internal,
            listOf(emulated)
        )
        assertEquals(internal, dir)
    }

    @Test
    fun sdPreferenceFallsBackToInternalWhenNoCandidateAtAll() {
        val dir = ThumbnailStoragePolicy.resolveDir(
            ThumbnailStoragePolicy.Location.SD_CARD,
            internal,
            emptyList()
        )
        assertEquals(internal, dir)
    }

    @Test
    fun storedLocationFallsBackToDefaultForUnknownValue() {
        assertEquals(
            ThumbnailStoragePolicy.DEFAULT_LOCATION,
            ThumbnailStoragePolicy.locationOf("sd_card_typo")
        )
        assertEquals(
            ThumbnailStoragePolicy.DEFAULT_LOCATION,
            ThumbnailStoragePolicy.locationOf(null)
        )
        assertEquals(
            ThumbnailStoragePolicy.Location.SD_CARD,
            ThumbnailStoragePolicy.locationOf("SD_CARD")
        )
    }

    @Test
    fun storedCapacityFallsBackToDefaultForInvalidValue() {
        assertEquals(
            ThumbnailStoragePolicy.DEFAULT_CAPACITY_BYTES,
            ThumbnailStoragePolicy.capacityBytes(0L)
        )
        assertEquals(
            ThumbnailStoragePolicy.DEFAULT_CAPACITY_BYTES,
            ThumbnailStoragePolicy.capacityBytes(-5L)
        )
        assertEquals(256L * 1024 * 1024, ThumbnailStoragePolicy.capacityBytes(256L * 1024 * 1024))
    }

    @Test
    fun defaultCapacityIsOneOfTheChoices() {
        assertTrue(ThumbnailStoragePolicy.CAPACITY_CHOICES.contains(ThumbnailStoragePolicy.DEFAULT_CAPACITY_BYTES))
    }

    @Test
    fun labelsUseMbGbAndUnlimited() {
        assertEquals("80MB", ThumbnailStoragePolicy.label(80L * 1024 * 1024))
        assertEquals("256MB", ThumbnailStoragePolicy.label(256L * 1024 * 1024))
        assertEquals("1GB", ThumbnailStoragePolicy.label(1024L * 1024 * 1024))
        assertEquals("2GB", ThumbnailStoragePolicy.label(2L * 1024 * 1024 * 1024))
        assertEquals("無制限", ThumbnailStoragePolicy.label(ThumbnailStoragePolicy.UNLIMITED))
    }
}
