package com.example.kennys_dokidoki_wallpaper

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeJsPolicyTest {

    @Test
    fun catalogContainsBundledWorks() {
        val ids = ThreeJsCatalog.BUILT_IN_WORKS.map { it.id }
        assertEquals(listOf("32", "36", "38"), ids)
        assertEquals("threejs/works/32.json", ThreeJsCatalog.findById("32")?.assetPath)
        assertEquals("threejs/works/36.json", ThreeJsCatalog.findById("36")?.assetPath)
        assertEquals("threejs/works/38.json", ThreeJsCatalog.findById("38")?.assetPath)
    }

    @Test
    fun randomPickAvoidsRepeat() {
        val seeded = Random(42L)
        var currentId: String? = "32"
        repeat(40) {
            val next = ThreeJsPolicy.pickNextRandomWork(
                ThreeJsCatalog.BUILT_IN_WORKS,
                currentId
            ) { bound -> seeded.nextInt(bound) }
            assertNotNull(next)
            assertFalse(currentId == next!!.id)
            currentId = next.id
        }
    }

    @Test
    fun tagSelectionLocksForThreeJs() {
        assertEquals(
            TagSelectionState.LOCKED,
            ThreeJsPolicy.tagSelectionStateFor(WallpaperSourceKind.THREE_JS)
        )
        assertEquals(
            TagSelectionState.ENABLED,
            ThreeJsPolicy.tagSelectionStateFor(WallpaperSourceKind.IMAGES)
        )
    }

    @Test
    fun sourceKindRoundTrips() {
        assertEquals(WallpaperSourceKind.IMAGES, WallpaperSourceKind.fromStored(null))
        assertEquals(WallpaperSourceKind.IMAGES, WallpaperSourceKind.fromStored("UNKNOWN"))
        assertEquals(
            WallpaperSourceKind.THREE_JS,
            WallpaperSourceKind.fromStored(WallpaperSourceKind.THREE_JS.name)
        )
    }

    @Test
    fun autoSetNameAvoidsCollision() {
        assertEquals("three.js", ThreeJsPolicy.suggestAutoSetName(emptySet()))
        assertEquals(
            "three.js (2)",
            ThreeJsPolicy.suggestAutoSetName(setOf("three.js"))
        )
        assertEquals(
            "three.js",
            ThreeJsPolicy.suggestAutoSetName(
                setOf("three.js"),
                currentEditingName = "three.js"
            )
        )
    }

    @Test
    fun renderSizeCapsAndAlignsEven() {
        val dims = ThreeJsPolicy.computeRenderSize(1440, 3120)
        assertTrue(dims.height <= ThreeJsPolicy.MAX_RENDER_EDGE_PX)
        assertEquals(0, dims.width % 2)
        assertEquals(0, dims.height % 2)
        assertFalse(dims.width <= 0)
    }
}
