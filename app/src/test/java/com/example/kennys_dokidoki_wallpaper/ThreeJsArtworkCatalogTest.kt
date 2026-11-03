package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreeJsArtworkCatalogTest {
    @Test
    fun bundledProjectResolvesAsOneArtwork() {
        val artwork = ThreeJsArtworkCatalog.find(ThreeJsArtworkCatalog.DEFAULT_ARTWORK_ID)

        assertEquals("wallpaper/threejs/r110/app.json", artwork.projectAssetPath)
        assertEquals("001  ·  APP.JSON", artwork.caption)
    }

    @Test
    fun unknownArtworkFallsBackToTheBundledDefault() {
        assertEquals(
            ThreeJsArtworkCatalog.DEFAULT_ARTWORK_ID,
            ThreeJsArtworkCatalog.find("missing-artwork").id
        )
    }
}
