package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KitaLive2dPolicyTest {

    @Test
    fun neutralPoseKeepsFootAnchorStable() {
        val (u, v) = KitaLive2dPolicy.deformUv(0.40f, 0.96f, KitaPose(breath = 0f))
        assertEquals(0.40f, u, 0.0001f)
        assertEquals(0.96f, v, 0.0001f)
    }

    @Test
    fun headTurnMovesFaceAndPonytailResponds() {
        val neutral = KitaLive2dPolicy.deformUv(0.36f, 0.16f, KitaPose(breath = 0f))
        val turned = KitaLive2dPolicy.deformUv(
            0.36f,
            0.16f,
            KitaPose(angleX = 0.8f, angleY = -0.5f, angleZ = 0.6f, breath = 0f)
        )
        val tailStill = KitaLive2dPolicy.deformUv(0.54f, 0.30f, KitaPose(breath = 0f))
        val tailSwinging = KitaLive2dPolicy.deformUv(
            0.54f,
            0.30f,
            KitaPose(sidePonytail = 0.8f, breath = 0f)
        )
        assertNotEquals(neutral, turned)
        assertNotEquals(tailStill.first, tailSwinging.first)
    }

    @Test
    fun faceAndBodyUseTheSameMeshDeformation() {
        val pose = KitaPose(angleX = -0.6f, angleY = 0.4f, angleZ = -0.7f, breath = 0.8f)
        val vertices = FloatArray((KitaLive2dPolicy.MESH_COLS + 1) * (KitaLive2dPolicy.MESH_ROWS + 1) * 2)
        KitaLive2dPolicy.fillBodyMesh(pose, 100f, 40f, 300f, 620f, vertices)

        val (u, v) = KitaLive2dPolicy.deformUv(0f, 0f, pose)
        assertEquals(100f + u * 300f, vertices[0], 0.01f)
        assertEquals(40f + v * 620f, vertices[1], 0.01f)

        val (lastU, lastV) = KitaLive2dPolicy.deformUv(1f, 1f, pose)
        assertEquals(100f + lastU * 300f, vertices[vertices.lastIndex - 1], 0.01f)
        assertEquals(40f + lastV * 620f, vertices[vertices.lastIndex], 0.01f)
    }

    @Test
    fun blueGuitarAxisRemainsRigidDuringPerformance() {
        val pose = KitaPose(
            breath = 1f,
            hairSideMid = -1f,
            hairSideTip = -1f,
            guitarRock = 0.9f
        )
        val p1 = KitaLive2dPolicy.deformUv(0.415f, 0.4825f, pose)
        val p2 = KitaLive2dPolicy.deformUv(0.625f, 0.3895f, pose)
        val p3 = KitaLive2dPolicy.deformUv(0.835f, 0.2965f, pose)
        val slope12 = (p2.second - p1.second) / (p2.first - p1.first)
        val slope23 = (p3.second - p2.second) / (p3.first - p2.first)
        assertEquals(slope12, slope23, 0.003f)
    }

    @Test
    fun tapZonesAndBlinkExpressionsStayCharacterSpecific() {
        assertEquals(KitaHitZone.HEAD, KitaLive2dPolicy.hitZone(0.36f, 0.16f))
        assertEquals(KitaHitZone.GUITAR, KitaLive2dPolicy.hitZone(0.45f, 0.50f))
        assertEquals(KitaHitZone.STAGE, KitaLive2dPolicy.hitZone(0.04f, 0.90f))
        assertEquals(KitaExpression.HALF_BLINK, KitaLive2dPolicy.activeExpression(KitaExpression.PERFORMANCE, 0.5f))
        assertEquals(KitaExpression.BLINK_CLOSED, KitaLive2dPolicy.activeExpression(KitaExpression.CHEERFUL, 0.1f))
        assertEquals(KitaExpression.BLINK_CLOSED, KitaLive2dPolicy.activeExpression(KitaExpression.SPARKLE, 0.1f))
        assertEquals(KitaExpression.SPARKLE, KitaLive2dPolicy.activeExpression(KitaExpression.SPARKLE, 1f))
    }

    @Test
    fun physicsTracksScrollTapAndBlink() {
        val engine = KitaPhysicsEngine()
        engine.onScroll(deltaPx = 120f, viewWidth = 1000f)
        val moving = engine.step(0.016f)
        assertEquals(KitaExpression.PERFORMANCE, moving.expression)
        assertTrue(abs(moving.hairSideMid) > 0f)
        assertTrue(abs(moving.laserTilt) > 0f)

        engine.onTapZone(KitaHitZone.HEAD, normX = 0.4f, normY = 0.2f)
        assertEquals(KitaExpression.SPARKLE, engine.step(0.016f).expression)

        var minEyeOpen = 1f
        repeat(350) {
            minEyeOpen = minOf(minEyeOpen, engine.step(0.02f).eyeOpen)
        }
        assertTrue(minEyeOpen < 0.25f)
    }
}

class HomeStageCharacterPolicyTest {

    @Test
    fun toggleCyclesBetweenBocchiAndKita() {
        assertEquals(HomeStageCharacter.KITA, HomeStageCharacterPolicy.next(HomeStageCharacter.BOCCHI))
        assertEquals(HomeStageCharacter.BOCCHI, HomeStageCharacterPolicy.next(HomeStageCharacter.KITA))
    }

    @Test
    fun unknownSavedCharacterFallsBackToBocchi() {
        assertEquals(HomeStageCharacter.BOCCHI, HomeStageCharacterPolicy.fromKey(null))
        assertEquals(HomeStageCharacter.BOCCHI, HomeStageCharacterPolicy.fromKey("REMOVED"))
        assertEquals(HomeStageCharacter.KITA, HomeStageCharacterPolicy.fromKey("KITA"))
    }
}
