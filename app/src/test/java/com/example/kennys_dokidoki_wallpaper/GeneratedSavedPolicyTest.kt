package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedSavedPolicyTest {
    private val saved = setOf("generated:2026-10-02/image.png")

    @Test
    fun matchesDespiteHostAndToken() {
        assertTrue(GeneratedSavedPolicy.isSaved("http://pc/api/v1/files/2026-10-02/image.png?token=old", saved))
        assertTrue(GeneratedSavedPolicy.isSaved("https://new-pc/api/v1/files/2026-10-02/image.png?token=new", saved))
    }

    @Test
    fun excludesOtherDateAndName() {
        assertFalse(GeneratedSavedPolicy.isSaved("http://pc/api/v1/files/2026-10-01/image.png", saved))
        assertFalse(GeneratedSavedPolicy.isSaved("http://pc/api/v1/files/2026-10-02/other.png", saved))
    }

    @Test
    fun excludesCacheAndLocalUris() {
        assertFalse(GeneratedSavedPolicy.isSaved("http://pc/api/v1/mobile-thumbnails/2026-10-02/image.png", saved))
        assertFalse(GeneratedSavedPolicy.isSaved("content://images/image.png", saved))
    }

    @Test
    fun clearsAfterLocalRemoval() {
        assertFalse(GeneratedSavedPolicy.isSaved("http://pc/api/v1/files/2026-10-02/image.png", emptySet()))
    }
}
