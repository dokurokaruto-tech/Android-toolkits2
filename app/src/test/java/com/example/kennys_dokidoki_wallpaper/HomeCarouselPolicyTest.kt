package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCarouselPolicyTest {

    @Test
    fun centeredItemIsFullSize() {
        val ratio = HomeCarouselPolicy.offsetRatio(itemCenter = 500f, listCenter = 500f, itemSpan = 200f)
        assertEquals(0f, ratio, 0.001f)
        assertEquals(1f, HomeCarouselPolicy.scale(ratio), 0.001f)
        assertEquals(1f, HomeCarouselPolicy.alpha(ratio), 0.001f)
    }

    @Test
    fun neighbourIsShrunkAndFaded() {
        val ratio = HomeCarouselPolicy.offsetRatio(itemCenter = 700f, listCenter = 500f, itemSpan = 200f)
        assertEquals(1f, ratio, 0.001f)
        assertTrue(HomeCarouselPolicy.scale(ratio) < 1f)
        assertTrue(HomeCarouselPolicy.alpha(ratio) < 1f)
    }

    @Test
    fun ratioIsClampedForFarItems() {
        val ratio = HomeCarouselPolicy.offsetRatio(itemCenter = 5000f, listCenter = 500f, itemSpan = 200f)
        assertEquals(1f, ratio, 0.001f)
    }

    @Test
    fun zeroSpanKeepsItemVisible() {
        assertEquals(0f, HomeCarouselPolicy.offsetRatio(100f, 500f, 0f), 0.001f)
    }

    @Test
    fun sidePaddingCentersOneItem() {
        assertEquals(200, HomeCarouselPolicy.sidePadding(listWidth = 600, itemWidth = 200))
        assertEquals(0, HomeCarouselPolicy.sidePadding(listWidth = 100, itemWidth = 200))
    }

    @Test
    fun firstSectionIsAllImagesAndSecondIsSets() {
        assertEquals(HomeSection.ALL_IMAGES, HomeSection.entries.first())
        assertEquals(HomeSection.IMAGE_SETS, HomeSection.entries[1])
        assertEquals(5, HomeSection.entries.size)
    }

    @Test
    fun unknownKeyFallsBackToAllImages() {
        assertEquals(HomeSection.ALL_IMAGES, HomeSection.of(null))
        assertEquals(HomeSection.BUILDER, HomeSection.of("BUILDER"))
    }
}
