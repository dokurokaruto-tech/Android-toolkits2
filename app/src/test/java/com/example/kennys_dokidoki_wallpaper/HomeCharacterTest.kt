package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        BocchiExpression.entries.forEach {
            val expected = it.key?.let { key -> "live2d/bocchi/bocchi_face_$key.webp" }
            assertEquals(expected, HomeCharacter.HITORI.faceAsset(it))
        }
    }

    @Test
    fun kitaHeadTapWinks() {
        val engine = BocchiPhysicsEngine()
        engine.onCharacterChange(HomeCharacter.KITA)
        engine.onTapZone(BocchiHitZone.HEAD_PANIC, 0.4f, 0.2f)
        val pose = HomeCharacter.KITA.adaptPose(engine.step(0.016f))
        assertEquals(BocchiExpression.WINK, pose.expression)
        assertEquals(0f, pose.glitchIntensity, 0f)
        assertEquals(0f, pose.cubeSwing, 0f)
        assertEquals(0f, pose.ahoge, 0f)
    }

    @Test
    fun nijikaHeadTapWinksAndKeepsAhoge() {
        val engine = BocchiPhysicsEngine()
        engine.onCharacterChange(HomeCharacter.NIJIKA)
        engine.onTapZone(BocchiHitZone.HEAD_PANIC, 0.4f, 0.2f)
        val pose = HomeCharacter.NIJIKA.adaptPose(engine.step(0.016f))
        assertEquals(BocchiExpression.WINK, pose.expression)
        assertEquals(Instrument.DRUMS, pose.instrument)
        // ドラムの両手は動き、アホ毛は残す
        var maxArmLeft = 0f
        var maxAhoge = 0f
        repeat(600) {
            val stepped = HomeCharacter.NIJIKA.adaptPose(engine.step(0.016f))
            maxArmLeft = maxOf(maxArmLeft, kotlin.math.abs(stepped.armLeft))
            maxAhoge = maxOf(maxAhoge, kotlin.math.abs(stepped.ahoge))
        }
        assertTrue(maxArmLeft > 0.1f)
        assertTrue(maxAhoge > 0f)
    }

    @Test
    fun ryoHeadTapPanicsAndBassHasNoDrumArms() {
        val engine = BocchiPhysicsEngine()
        engine.onCharacterChange(HomeCharacter.RYO)
        engine.onTapZone(BocchiHitZone.HEAD_PANIC, 0.4f, 0.2f)
        val pose = HomeCharacter.RYO.adaptPose(engine.step(0.016f))
        assertEquals(BocchiExpression.BOCCHI_PANIC, pose.expression)
        assertEquals(0f, pose.armLeft, 0f)
        assertEquals(Instrument.GUITAR, pose.instrument)
    }

    @Test
    fun stageTapSurprisesEveryCharacter() {
        HomeCharacter.entries.forEach { character ->
            val engine = BocchiPhysicsEngine()
            engine.onCharacterChange(character)
            engine.onTapZone(BocchiHitZone.STAGE_BURST, 0.1f, 0.9f)
            assertEquals(BocchiExpression.SURPRISE, character.adaptPose(engine.step(0.016f)).expression)
        }
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
