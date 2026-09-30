package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionOrderTest {

    private val all = HomeSection.entries

    @Test
    fun savedOrderIsKept() {
        val saved = listOf(HomeSection.DIARY.name, HomeSection.SETTINGS.name)
        val merged = HomeSectionOrder.merge(saved, all)
        assertEquals(HomeSection.DIARY, merged[0])
        assertEquals(HomeSection.SETTINGS, merged[1])
        assertEquals(all.size, merged.size)
    }

    @Test
    fun newSectionGoesToTheEnd() {
        val saved = all.filterNot { it == HomeSection.DIARY }.map { it.name }
        val merged = HomeSectionOrder.merge(saved, all)
        assertEquals(HomeSection.DIARY, merged.last())
    }

    @Test
    fun unknownAndDuplicateNamesAreDropped() {
        val saved = listOf("GONE_FEATURE", HomeSection.TAGS.name, HomeSection.TAGS.name)
        val merged = HomeSectionOrder.merge(saved, all)
        assertEquals(HomeSection.TAGS, merged.first())
        assertEquals(all.size, merged.size)
    }

    @Test
    fun moveReordersOneItem() {
        val order = listOf(HomeSection.ALL_IMAGES, HomeSection.TAGS, HomeSection.DIARY)
        assertEquals(
            listOf(HomeSection.TAGS, HomeSection.ALL_IMAGES, HomeSection.DIARY),
            HomeSectionOrder.move(order, 0, 1)
        )
    }

    @Test
    fun moveOutOfRangeKeepsOrder() {
        val order = listOf(HomeSection.ALL_IMAGES, HomeSection.TAGS)
        assertEquals(order, HomeSectionOrder.move(order, 0, 5))
        assertEquals(order, HomeSectionOrder.move(order, 1, 1))
    }
}
