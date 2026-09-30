package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BocchiLive2dPolicyTest {

    @Test
    fun neutralPoseKeepsFootAnchorStable() {
        val pose = BocchiPose(breath = 0f)
        val (u, v) = BocchiLive2dPolicy.deformUv(0.40f, 0.95f, pose)
        assertEquals(0.40f, u, 0.0001f)
        assertEquals(0.95f, v, 0.0001f)
    }

    @Test
    fun headTurnAndTiltDisplaceFaceRegion() {
        val neutral = BocchiLive2dPolicy.deformUv(0.40f, 0.18f, BocchiPose(breath = 0f))
        val turned = BocchiLive2dPolicy.deformUv(
            0.40f,
            0.18f,
            BocchiPose(angleX = 0.8f, angleY = -0.5f, angleZ = 0.6f, breath = 0f)
        )
        assertTrue(turned.first > neutral.first)
        assertNotEquals(neutral.second, turned.second)
    }

    @Test
    fun faceSubMeshMatchesBodyDeformationAtBoundaries() {
        val pose = BocchiPose(
            angleX = -0.65f,
            angleY = 0.40f,
            angleZ = -0.75f,
            bodyAngleX = 0.35f,
            bodyAngleZ = 0.25f,
            breath = 0.9f
        )
        val faceVerts = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)
        BocchiLive2dPolicy.fillFaceMesh(pose, left = 100f, top = 50f, width = 500f, height = 800f, outVerts = faceVerts)

        val (u0, v0) = BocchiLive2dPolicy.deformUv(BocchiLive2dPolicy.FACE_U0, BocchiLive2dPolicy.FACE_V0, pose)
        assertEquals(100f + u0 * 500f, faceVerts[0], 0.01f)
        assertEquals(50f + v0 * 800f, faceVerts[1], 0.01f)

        val lastIdx = faceVerts.size - 2
        val (u1, v1) = BocchiLive2dPolicy.deformUv(BocchiLive2dPolicy.FACE_U1, BocchiLive2dPolicy.FACE_V1, pose)
        assertEquals(100f + u1 * 500f, faceVerts[lastIdx], 0.01f)
        assertEquals(50f + v1 * 800f, faceVerts[lastIdx + 1], 0.01f)
    }

    @Test
    fun rigidGuitarAxisStaysStraightDuringGuitarRockAndHairWave() {
        val pose = BocchiPose(
            breath = 1.0f,
            hairSideMid = -1.0f,
            hairSideTip = -1.0f,
            guitarRock = 0.9f
        )
        // ギター中心軸上の3点 (t = 0.15, 0.50, 0.85)
        val p1 = BocchiLive2dPolicy.deformUv(0.365f, 0.605f, pose)
        val p2 = BocchiLive2dPolicy.deformUv(0.610f, 0.500f, pose)
        val p3 = BocchiLive2dPolicy.deformUv(0.855f, 0.395f, pose)

        val slope12 = (p2.second - p1.second) / (p2.first - p1.first)
        val slope23 = (p3.second - p2.second) / (p3.first - p2.first)
        assertEquals(slope12, slope23, 0.002f)
    }

    @Test
    fun hitZoneClassifiesHeadGuitarAndStage() {
        assertEquals(BocchiHitZone.HEAD_PANIC, BocchiLive2dPolicy.hitZone(0.40f, 0.18f))
        assertEquals(BocchiHitZone.GUITAR_STRUM, BocchiLive2dPolicy.hitZone(0.45f, 0.55f))
        assertEquals(BocchiHitZone.STAGE_BURST, BocchiLive2dPolicy.hitZone(0.08f, 0.88f))
    }

    @Test
    fun activeExpressionHandlesBlinkThresholdsAndPanicOverride() {
        assertEquals(
            BocchiExpression.SHY_BASE,
            BocchiLive2dPolicy.activeExpression(BocchiExpression.SHY_BASE, eyeOpen = 0.95f)
        )
        assertEquals(
            BocchiExpression.HALF_BLINK,
            BocchiLive2dPolicy.activeExpression(BocchiExpression.HAPPY_SMILE, eyeOpen = 0.50f)
        )
        assertEquals(
            BocchiExpression.BLINK_CLOSED,
            BocchiLive2dPolicy.activeExpression(BocchiExpression.AWAKENED_GROOVE, eyeOpen = 0.10f)
        )
        assertEquals(
            BocchiExpression.BOCCHI_PANIC,
            BocchiLive2dPolicy.activeExpression(BocchiExpression.BOCCHI_PANIC, eyeOpen = 0.05f)
        )
    }

    @Test
    fun physicsEngineRespondsToScrollTapAndBlinksOverTime() {
        val engine = BocchiPhysicsEngine()
        engine.onScroll(deltaPx = 120f, viewWidth = 1000f)
        val grooved = engine.step(0.016f)
        assertEquals(BocchiExpression.AWAKENED_GROOVE, grooved.expression)
        assertTrue(abs(grooved.hairSideMid) > 0f)
        assertTrue(abs(grooved.laserTilt) > 0f)

        engine.onTapZone(BocchiHitZone.HEAD_PANIC, normX = 0.4f, normY = 0.2f)
        val panicked = engine.step(0.016f)
        assertEquals(BocchiExpression.BOCCHI_PANIC, panicked.expression)
        assertTrue(panicked.glitchIntensity > 0.5f)

        // パニック収束後、自動瞬きが発生することを確認
        var minEyeOpen = 1f
        for (i in 0 until 220) {
            val pose = engine.step(0.020f)
            if (pose.eyeOpen < minEyeOpen) {
                minEyeOpen = pose.eyeOpen
            }
        }
        assertTrue(minEyeOpen < 0.25f)
    }
}
