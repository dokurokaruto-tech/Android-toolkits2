package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeJsInspectorPolicyTest {

    @Test
    fun browserInputResolvesGoogle() {
        assertEquals("https://www.google.com/", ThreeJsInspectorPolicy.resolveBrowserInput(""))
        assertEquals("https://www.google.com/", ThreeJsInspectorPolicy.resolveBrowserInput("   "))
        assertEquals(
            "https://threejs.org/examples",
            ThreeJsInspectorPolicy.resolveBrowserInput("threejs.org/examples")
        )
        assertEquals(
            "http://localhost:8080/app.json",
            ThreeJsInspectorPolicy.resolveBrowserInput("localhost:8080/app.json")
        )
        assertEquals(
            "https://www.google.com/search?q=three.js+app.json",
            ThreeJsInspectorPolicy.resolveBrowserInput("three.js app.json")
        )
    }

    @Test
    fun detectsThreeAppSceneAndGeo() {
        val appJson = """
            {
              "metadata": { "type": "App", "generator": "Object3D.toJSON" },
              "project": { "title": "Neon Spiral", "shadows": true },
              "camera": { "object": { "type": "PerspectiveCamera" } },
              "scene": {
                "geometries": [{ "uuid": "g1" }, { "uuid": "g2" }],
                "materials": [{ "uuid": "m1" }],
                "images": [{ "uuid": "i1", "url": "data:image/png;base64,AAAA" }],
                "object": { "type": "Scene" }
              },
              "scripts": {
                "u1": [{ "name": "spin", "source": "function update(){}" }]
              }
            }
        """.trimIndent()

        val appRes = ThreeJsInspectorPolicy.inspectJsonText(appJson, "https://x.dev/038/app.json")
        assertEquals(ThreeJsJsonKind.THREE_APP, appRes.kind)
        assertTrue(appRes.kind.isThreeJsPlayable())
        assertEquals("Neon Spiral", appRes.suggestedTitle)
        assertEquals(1, appRes.scriptCount)
        assertEquals(2, appRes.geometryCount)
        assertEquals(1, appRes.materialCount)
        assertEquals(1, appRes.imageCount)

        val sceneJson = """
            {
              "metadata": { "version": 4.5, "type": "Object" },
              "geometries": [{ "uuid": "g1", "type": "BoxGeometry" }],
              "object": { "uuid": "o1", "type": "Scene", "name": "CrystalRoom" }
            }
        """.trimIndent()
        val sceneRes = ThreeJsInspectorPolicy.inspectJsonText(sceneJson)
        assertEquals(ThreeJsJsonKind.THREE_SCENE, sceneRes.kind)
        assertEquals("CrystalRoom", sceneRes.suggestedTitle)

        val geoJson = """
            {
              "metadata": { "version": 4.5, "type": "BufferGeometry" },
              "data": { "attributes": { "position": { "itemSize": 3, "array": [0,0,0,1,0,0,0,1,0] } } }
            }
        """.trimIndent()
        val geoRes = ThreeJsInspectorPolicy.inspectJsonText(geoJson, "https://x.dev/models/suzanne.json")
        assertEquals(ThreeJsJsonKind.THREE_GEOMETRY, geoRes.kind)
        assertEquals("suzanne", geoRes.suggestedTitle)
    }

    @Test
    fun rejectsMalformedJsonAndHtml() {
        val htmlRes = ThreeJsInspectorPolicy.inspectJsonText("<!DOCTYPE html><html></html>")
        assertEquals(ThreeJsJsonKind.NOT_JSON, htmlRes.kind)
        assertFalse(htmlRes.kind.isValidJson())

        val brokenRes = ThreeJsInspectorPolicy.inspectJsonText("""{"metadata": {"type": "App", }""")
        assertEquals(ThreeJsJsonKind.NOT_JSON, brokenRes.kind)
    }

    @Test
    fun compactsAndRestoresBlobs() {
        val longBase64 = "A".repeat(900)
        val raw = """{"images":[{"url":"data:image/png;base64,$longBase64"}],"title":"Cube"}"""
        val doc = ThreeJsInspectorPolicy.compactForEditor(raw)

        assertTrue(doc.placeholders.isNotEmpty())
        assertFalse(doc.displayText.contains(longBase64))
        assertTrue(ThreeJsInspectorPolicy.inspectJsonText(doc.displayText).kind.isValidJson())

        val edited = doc.displayText.replace("\"Cube\"", "\"Prism\"")
        val restored = ThreeJsInspectorPolicy.expandFromEditor(edited, doc)
        assertTrue(restored.contains("data:image/png;base64,$longBase64"))
        assertTrue(restored.contains("\"Prism\""))
        assertTrue(ThreeJsInspectorPolicy.inspectJsonText(restored).kind.isValidJson())
    }

    @Test
    fun ranksAndFiltersNetworkItems() {
        val appInspect = ThreeJsInspectorPolicy.inspectJsonText(
            """{"metadata":{"type":"App"},"camera":{},"scene":{}}"""
        )
        val appBadge = ThreeJsInspectorPolicy.classifyNetworkItem(
            "https://mrdoob.com/038/app.json",
            "application/json",
            appInspect
        )
        val jsBadge = ThreeJsInspectorPolicy.classifyNetworkItem(
            "https://mrdoob.com/js/three.min.js",
            "application/javascript",
            null
        )
        assertEquals(NetworkResourceBadge.THREE_APP, appBadge)
        assertEquals(NetworkResourceBadge.SCRIPT, jsBadge)
        assertTrue(appBadge.priorityRank() < jsBadge.priorityRank())
        assertTrue(ThreeJsInspectorPolicy.matchesFilter(appBadge, NetworkFilterKind.THREE_CANDIDATES))
        assertFalse(ThreeJsInspectorPolicy.matchesFilter(jsBadge, NetworkFilterKind.THREE_CANDIDATES))
    }

    @Test
    fun formatsTileCaptionCleanly() {
        val work = ThreeJsWork(
            id = "38",
            title = "038",
            assetPath = "threejs/works/38.json",
            dateLabel = "21 03 10"
        )
        assertEquals("038 ~ 21 03 10", ThreeJsPolicy.formatTileCaption(3, work))
    }
}
