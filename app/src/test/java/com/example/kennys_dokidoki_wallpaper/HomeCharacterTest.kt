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
        BocchiExpression.entries.forEach { expression ->
            val expected = when {
                expression.suffix == null -> null
                expression == BocchiExpression.BOCCHI_PANIC -> "live2d/bocchi/bocchi_face_panic.webp"
                else -> "live2d/bocchi/bocchi_face_${expression.suffix}.webp"
            }
            assertEquals(expected, HomeCharacter.HITORI.faceAsset(expression))
        }
    }

    @Test
    fun kitaHeadTapCheers() {
        val tap = HomeCharacter.KITA.tapZone(BocchiHitZone.HEAD_PANIC)
        assertEquals(BocchiHitZone.HEAD_REACT, tap)

        val engine = BocchiPhysicsEngine()
        engine.onTapZone(tap, 0.4f, 0.2f)
        val pose = HomeCharacter.KITA.adaptPose(engine.step(0.016f))
        // PANIC スロットに入るが、喜多の絵は cheer（満面の笑み）でグリッチは出ない
        assertEquals(BocchiExpression.BOCCHI_PANIC, pose.expression)
        assertEquals("live2d/kita/kita_face_cheer.webp", HomeCharacter.KITA.faceAsset(pose.expression))
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

    @Test
    fun nijikaStrumBecomesDrumHits() {
        val adapted = HomeCharacter.NIJIKA.adaptPose(
            BocchiPose(armStrum = 0.8f, guitarRock = 0.9f, hairSideTip = 0.5f, glitchIntensity = 1f)
        )
        assertEquals(0.8f, adapted.drumHitL, 0f)
        assertEquals(0f, adapted.drumHitR, 0f)
        assertEquals(0f, adapted.armStrum, 0f)
        assertEquals(0f, adapted.guitarRock, 0f)
        assertEquals(0f, adapted.glitchIntensity, 0f)
        assertEquals(0.65f, adapted.ponySwing, 0.0001f)
        assertEquals(BocchiHitZone.HEAD_REACT, HomeCharacter.NIJIKA.tapZone(BocchiHitZone.HEAD_PANIC))

        val engine = BocchiPhysicsEngine()
        repeat(600) {
            val pose = HomeCharacter.NIJIKA.adaptPose(engine.step(0.016f))
            val vertices = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
            BocchiLive2dPolicy.fillBodyMesh(pose, 0f, 0f, 592f, 750f, vertices)
            assertTrue(vertices.all { it.isFinite() })
        }
    }

    @Test
    fun ryoStaysCalm() {
        val raw = BocchiPose(
            beatBounce = -1f, hairSideMid = 1f, guitarRock = 1f, glitchIntensity = 1f
        )
        val adapted = HomeCharacter.RYO.adaptPose(raw)
        assertEquals(-0.55f, adapted.beatBounce, 0f)
        assertEquals(0.35f, adapted.hairSideMid, 0f)
        assertEquals(0.7f, adapted.guitarRock, 0f)
        assertEquals(0f, adapted.glitchIntensity, 0f)
    }

    @Test
    fun sparkleAndHudFollowFaces() {
        // ウィンク・閉眼の顔では瞳ハイライトを出さない
        assertEquals(EyeSparkle.LEFT_ONLY, HomeCharacter.KITA.sparkle(BocchiExpression.AWAKENED_GROOVE))
        assertEquals(EyeSparkle.RIGHT_ONLY, HomeCharacter.KITA.sparkle(BocchiExpression.IDLE_CHARM))
        assertEquals(EyeSparkle.NONE, HomeCharacter.NIJIKA.sparkle(BocchiExpression.HAPPY_SMILE))
        assertEquals(EyeSparkle.BOTH, HomeCharacter.RYO.sparkle(BocchiExpression.AWAKENED_GROOVE))
        HomeCharacter.entries.forEach { character ->
            BocchiExpression.entries.forEach { assertTrue(character.hudLabel(it).isNotBlank()) }
        }
    }
}
