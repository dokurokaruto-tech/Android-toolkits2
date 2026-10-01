package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeCharacterTest {
    @Test
    fun restoreSelectionSafely() {
        assertEquals(HomeCharacter.HITORI, HomeCharacter.fromSaved(null))
        assertEquals(HomeCharacter.HITORI, HomeCharacter.fromSaved("removed"))
        HomeCharacter.entries.forEach {
            assertEquals(it, HomeCharacter.fromSaved(it.name))
        }
    }

    @Test
    fun assetsBelongToCharacter() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        HomeCharacter.entries.forEach { character ->
            assertTrue(File(assets, character.bodyAsset).isFile)
            BocchiExpression.entries.forEach { expression ->
                val path = character.faceAsset(expression)
                if (expression == BocchiExpression.SHY_BASE) {
                    assertNull(path)
                } else {
                    assertNotNull(path)
                    assertTrue(File(assets, path!!).isFile)
                    assertEquals(character.bodyAsset.substringBeforeLast('/'), path.substringBeforeLast('/'))
                }
            }
        }
    }

    @Test
    fun hitoriKeepsOriginalReactions() {
        val pose = BocchiPose(cubeSwing = 0.7f, glitchIntensity = 1f)
        assertSame(pose, HomeCharacter.HITORI.adaptPose(pose))
        BocchiHitZone.entries.forEach { assertEquals(it, HomeCharacter.HITORI.tapZone(it)) }
        BocchiExpression.entries.forEach { assertEquals(it.assetName, HomeCharacter.HITORI.faceAsset(it)) }
    }

    @Test
    fun kitaHeadTapWinks() {
        val engine = BocchiPhysicsEngine()
        engine.onTapZone(HomeCharacter.KITA.tapZone(BocchiHitZone.HEAD_PANIC), 0.4f, 0.2f)
        val pose = HomeCharacter.KITA.adaptPose(engine.step(0.016f))
        assertEquals(BocchiExpression.AWAKENED_GROOVE, pose.expression)
        assertEquals(0f, pose.glitchIntensity, 0f)
        assertEquals(0f, pose.cubeSwing, 0f)
        assertEquals(0f, pose.ahoge, 0f)
    }

    @Test
    fun kitaStillBlinksAndMoves() {
        val engine = BocchiPhysicsEngine()
        var blinkSeen = false
        var hairMoved = false
        repeat(600) {
            val pose = HomeCharacter.KITA.adaptPose(engine.step(0.016f))
            blinkSeen = blinkSeen || pose.expression == BocchiExpression.BLINK_CLOSED
            hairMoved = hairMoved || kotlin.math.abs(pose.hairSideMid) > 0.05f
            assertEquals(0f, pose.glitchIntensity, 0f)
            val vertices = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
            BocchiLive2dPolicy.fillBodyMesh(pose, 0f, 0f, 592f, 750f, vertices)
            assertTrue(vertices.all { it.isFinite() })
        }
        assertTrue(blinkSeen)
        assertTrue(hairMoved)
    }
}
