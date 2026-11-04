package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeJsSetPolicyTest {

    @Test
    fun `single work always returns index zero`() {
        assertEquals(0, ThreeJsSetPolicy.nextIndex(0, 1, 0))
        assertEquals(0, ThreeJsSetPolicy.nextIndex(0, 0, 5))
    }

    @Test
    fun `next work never repeats current`() {
        for (count in 2..6) {
            for (current in 0 until count) {
                for (pick in 0 until count - 1) {
                    val next = ThreeJsSetPolicy.nextIndex(current, count, pick)
                    assertTrue(next in 0 until count)
                    assertTrue(next != current)
                }
            }
        }
    }

    @Test
    fun `next work covers every other index exactly once`() {
        val count = 4
        for (current in 0 until count) {
            val seen = (0 until count - 1).map { ThreeJsSetPolicy.nextIndex(current, count, it) }.toSet()
            val expected = (0 until count).filter { it != current }.toSet()
            assertEquals(expected, seen)
        }
    }

    @Test
    fun `out of range random is coerced`() {
        assertEquals(2, ThreeJsSetPolicy.nextIndex(0, 3, 99))
        assertEquals(0, ThreeJsSetPolicy.nextIndex(2, 3, -5))
    }

    @Test
    fun `three js set locks tags and skips image match`() {
        assertTrue(ThreeJsSetPolicy.locksTags(true))
        assertFalse(ThreeJsSetPolicy.locksTags(false))
        assertFalse(ThreeJsSetPolicy.needsImageMatch(true))
        assertTrue(ThreeJsSetPolicy.needsImageMatch(false))
    }

    @Test
    fun `builtin catalog has three works with stable ids`() {
        assertEquals(3, ThreeJsWork.count)
        assertEquals("32", ThreeJsWork.at(0).id)
        assertEquals("36", ThreeJsWork.at(1).id)
        assertEquals("38", ThreeJsWork.at(2).id)
    }

    @Test
    fun `catalog index is coerced and id lookup round trips`() {
        assertEquals(ThreeJsWork.at(0), ThreeJsWork.at(-1))
        assertEquals(ThreeJsWork.at(2), ThreeJsWork.at(99))
        ThreeJsWork.entries.forEach { work ->
            assertEquals(work, ThreeJsWork.byId(work.id))
            assertEquals("threejs/works/${work.id}.json", work.assetPath)
            assertEquals("file:///android_asset/threejs/works/posters/${work.id}.webp", work.posterUri)
        }
        assertEquals(null, ThreeJsWork.byId("missing"))
    }
}
