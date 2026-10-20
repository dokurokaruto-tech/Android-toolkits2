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
                if (path != null) {
                    assertTrue(File(assets, path).isFile)
                    assertEquals(character.bodyAsset.substringBeforeLast('/'), path.substringBeforeLast('/'))
                }
            }
            // 閉じ目と開き目は必ず素材を持つ。瞬きが欠けるとキャラが固まる。
            assertNotNull(character.faceAsset(BocchiExpression.BLINK_CLOSED))
            assertNotNull(character.faceAsset(BocchiExpression.HALF_BLINK))
        }
    }

    @Test
    fun everyBandMemberHasItsOwnRig() {
        val boxes = HomeCharacter.entries.map { CharacterRigs.of(it).faceBox }
        assertEquals(HomeCharacter.entries.size, boxes.toSet().size)
        HomeCharacter.entries.forEach { character ->
            val rig = CharacterRigs.of(character)
            assertTrue(rig.faceBox.u1 > rig.faceBox.u0)
            assertTrue(rig.faceBox.v1 > rig.faceBox.v0)
            assertTrue(rig.faceBox.u1 <= 1f && rig.faceBox.v1 <= 1f)
            // ドラムか楽器のどちらかは必ず持つ。
            assertTrue(rig.limbs.isNotEmpty() || rig.instrument != null)
        }
    }

    @Test
    fun hitoriKeepsOriginalReactions() {
        val rig = CharacterRigs.HITORI
        assertEquals(0.13f, rig.head.halfWidthU, 0.0001f)
        assertEquals(0.32f, rig.head.bottomV, 0.0001f)
        assertEquals(160f / 592f, rig.faceBox.u0, 0.0001f)
        assertEquals(90f / 750f, rig.faceBox.v0, 0.0001f)
        BocchiHitZone.entries.forEach { assertEquals(it, HomeCharacter.HITORI.tapZone(it)) }
        assertEquals(
            BocchiExpression.LAUGH_JOY,
            HomeCharacter.HITORI.fallbackExpression(BocchiExpression.LAUGH_JOY)
        )
    }

    @Test
    fun kitaReplacesWinkAndKeepsMoving() {
        val engine = BocchiPhysicsEngine(MotionStyle.KITA)
        engine.onTapZone(HomeCharacter.KITA.tapZone(BocchiHitZone.HEAD_PANIC), 0.4f, 0.2f)
        val pose = engine.step(0.016f)
        assertEquals(BocchiExpression.WINK, pose.expression)
        assertEquals(0f, pose.glitchIntensity, 0f)
    }

    @Test
    fun everyCharacterBlinksAndMoves() {
        HomeCharacter.entries.forEach { character ->
            val engine = BocchiPhysicsEngine(character.motion)
            var blinkSeen = false
            var hairMoved = false
            repeat(600) {
                val pose = engine.step(0.016f)
                blinkSeen = blinkSeen || pose.expression == BocchiExpression.BLINK_CLOSED
                hairMoved = hairMoved || kotlin.math.abs(pose.hairSideMid) > 0.05f
                val vertices = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
                BocchiLive2dPolicy.fillBodyMesh(pose, character.rig, 0f, 0f, 592f, 750f, vertices)
                assertTrue(vertices.all { it.isFinite() })
            }
            assertTrue(character.name, blinkSeen)
            assertTrue(character.name, hairMoved)
        }
    }

    @Test
    fun nijikaStrikesTheDrumsOnBeat() {
        val engine = BocchiPhysicsEngine(MotionStyle.NIJIKA)
        val hits = mutableSetOf<DrumPad>()
        var stickMotion = 0f
        repeat(400) {
            val pose = engine.step(0.016f)
            if (pose.drumHit != DrumPad.NONE) {
                hits += pose.drumHit
            }
            stickMotion = maxOf(stickMotion, kotlin.math.abs(pose.stickArm))
        }
        assertTrue(hits.contains(DrumPad.SNARE))
        assertTrue(hits.contains(DrumPad.KICK))
        assertTrue(stickMotion > 0.5f)
    }
}
