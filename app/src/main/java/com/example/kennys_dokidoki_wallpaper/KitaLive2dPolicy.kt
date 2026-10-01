package com.example.kennys_dokidoki_wallpaper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class KitaExpression {
    CHEERFUL,
    HALF_BLINK,
    BLINK_CLOSED,
    PERFORMANCE,
    SPARKLE
}

internal enum class KitaHitZone {
    HEAD,
    GUITAR,
    STAGE
}

internal data class KitaPose(
    val angleX: Float = 0f,
    val angleY: Float = 0f,
    val angleZ: Float = 0f,
    val bodyAngleX: Float = 0f,
    val bodyAngleZ: Float = 0f,
    val breath: Float = 0.5f,
    val beatBounce: Float = 0f,
    val eyeOpen: Float = 1f,
    val eyeBallX: Float = 0f,
    val eyeBallY: Float = 0f,
    val hairFront: Float = 0f,
    val hairSideMid: Float = 0f,
    val hairSideTip: Float = 0f,
    val sidePonytail: Float = 0f,
    val guitarRock: Float = 0f,
    val armStrum: Float = 0f,
    val skirtSway: Float = 0f,
    val laserTilt: Float = 0f,
    val expression: KitaExpression = KitaExpression.CHEERFUL
) {
    fun stagePose(): BocchiPose = BocchiPose(
        angleX = angleX,
        angleY = angleY,
        angleZ = angleZ,
        bodyAngleX = bodyAngleX,
        bodyAngleZ = bodyAngleZ,
        breath = breath,
        beatBounce = beatBounce,
        eyeOpen = eyeOpen,
        eyeBallX = eyeBallX,
        eyeBallY = eyeBallY,
        hairFront = hairFront,
        hairSideMid = hairSideMid,
        hairSideTip = hairSideTip,
        guitarRock = guitarRock,
        armStrum = armStrum,
        skirtSway = skirtSway,
        laserTilt = laserTilt,
        expression = when (expression) {
            KitaExpression.HALF_BLINK -> BocchiExpression.HALF_BLINK
            KitaExpression.BLINK_CLOSED -> BocchiExpression.BLINK_CLOSED
            KitaExpression.PERFORMANCE -> BocchiExpression.AWAKENED_GROOVE
            KitaExpression.SPARKLE -> BocchiExpression.HAPPY_SMILE
            KitaExpression.CHEERFUL -> BocchiExpression.SHY_BASE
        }
    )
}

/** 喜多郁代の2.5Dメッシュ変形、タップ判定、表情ステート。 */
internal object KitaLive2dPolicy {

    const val MESH_COLS = 28
    const val MESH_ROWS = 36
    const val BODY_ASSET = "live2d/kita/kita_body_base.webp"

    const val EYE_LEFT_U = 0.285f
    const val EYE_RIGHT_U = 0.398f
    const val EYE_V = 0.139f
    const val EYE_WIDTH = 0.092f
    const val EYE_HEIGHT = 0.038f
    const val GUITAR_BRIDGE_U = 0.405f
    const val GUITAR_BRIDGE_V = 0.515f
    const val GUITAR_NUT_U = 0.935f
    const val GUITAR_NUT_V = 0.255f

    const val STAGE_BPM = 150f
    const val ACCENT_COLOR = HOME_KITA_ACCENT_COLOR
    const val GUITAR_COLOR = 0xFF74C7EB.toInt()
    const val EYE_LASH_COLOR = 0xFF573B36.toInt()
    const val SKIN_COLOR = 0xFFFFE5DA.toInt()

    private const val TEX_ASPECT = 415f / 738f
    private const val INV_ASPECT = 738f / 415f
    private const val GUITAR_AX = 0.24f
    private const val GUITAR_AY = 0.56f
    private const val GUITAR_BX = 0.94f
    private const val GUITAR_BY = 0.25f
    private const val HEAD_PIVOT_U = 0.365f
    private const val HEAD_PIVOT_V = 0.205f
    private const val BODY_PIVOT_U = 0.39f
    private const val BODY_PIVOT_V = 0.70f
    private const val HALF_BLINK_THRESHOLD = 0.68f
    private const val CLOSED_BLINK_THRESHOLD = 0.25f

    fun hitZone(u: Float, v: Float): KitaHitZone {
        if (u in 0.15f..0.62f && v in 0.025f..0.30f) {
            return KitaHitZone.HEAD
        }
        if (u in 0.08f..0.99f && v in 0.26f..0.69f) {
            return KitaHitZone.GUITAR
        }
        return KitaHitZone.STAGE
    }

    fun sectionExpression(section: HomeSection): KitaExpression = when (section) {
        HomeSection.BUILDER, HomeSection.TAGS -> KitaExpression.PERFORMANCE
        HomeSection.DIARY, HomeSection.IMAGE_SETS -> KitaExpression.SPARKLE
        HomeSection.ALL_IMAGES, HomeSection.SETTINGS -> KitaExpression.CHEERFUL
    }

    fun activeExpression(base: KitaExpression, eyeOpen: Float): KitaExpression {
        if (eyeOpen <= CLOSED_BLINK_THRESHOLD) {
            return KitaExpression.BLINK_CLOSED
        }
        if (eyeOpen <= HALF_BLINK_THRESHOLD) {
            return KitaExpression.HALF_BLINK
        }
        return base
    }

    fun deformUv(u: Float, v: Float, pose: KitaPose): Pair<Float, Float> {
        var du = 0f
        var dv = 0f
        val guitarW = guitarWeight(u, v)

        // Gentle shoulder sway keeps the instrument stable while the torso breathes.
        val upperW = 1f - smoothstep(0.73f, 0.90f, v)
        if (upperW > 0f) {
            val relX = (u - BODY_PIVOT_U) * TEX_ASPECT
            val relY = v - BODY_PIVOT_V
            val bodyRad = pose.bodyAngleZ * 0.038f * upperW
            val cosBody = cos(bodyRad)
            val sinBody = sin(bodyRad)
            du += ((relX * cosBody - relY * sinBody - relX) * INV_ASPECT) +
                pose.bodyAngleX * 0.014f * upperW
            dv += (relX * sinBody + relY * cosBody - relY) +
                pose.beatBounce * 0.008f * upperW
        }

        val breathW = gaussian2d(u, v, 0.39f, 0.39f, 0.18f, 0.11f) * (1f - guitarW)
        du += (u - 0.39f) * 0.018f * pose.breath * breathW
        dv -= 0.006f * pose.breath * breathW

        // A small 2.5D head turn follows touch and carousel movement.
        val headW = (1f - smoothstep(0.25f, 0.33f, v)) *
            smoothstep(0.12f, 0.21f, u) *
            (1f - smoothstep(0.53f, 0.64f, u))
        if (headW > 0f) {
            val rx = (u - HEAD_PIVOT_U) * TEX_ASPECT
            val ry = v - HEAD_PIVOT_V
            val headRad = pose.angleZ * 0.075f * headW
            val cosHead = cos(headRad)
            val sinHead = sin(headRad)
            du += (rx * cosHead - ry * sinHead - rx) * INV_ASPECT
            dv += rx * sinHead + ry * cosHead - ry
            val faceW = gaussian2d(u, v, 0.36f, 0.16f, 0.13f, 0.08f)
            du += pose.angleX * (0.010f * headW + 0.008f * faceW)
            dv += pose.angleY * (0.009f * headW + 0.006f * faceW)
        }

        // The side ponytail and loose locks trail the head on separate spring chains.
        val ponytailW = gaussian2d(u, v, 0.52f, 0.22f, 0.075f, 0.12f) +
            gaussian2d(u, v, 0.56f, 0.34f, 0.055f, 0.10f)
        du += pose.sidePonytail * 0.032f * ponytailW
        dv += abs(pose.sidePonytail) * 0.008f * ponytailW

        val leftHairW = (1f - smoothstep(0.18f, 0.28f, u)) *
            smoothstep(0.17f, 0.26f, v) *
            (1f - smoothstep(0.48f, 0.58f, v))
        val rightHairW = smoothstep(0.47f, 0.54f, u) *
            (1f - smoothstep(0.64f, 0.72f, u)) *
            smoothstep(0.17f, 0.26f, v) *
            (1f - smoothstep(0.49f, 0.59f, v))
        val hairW = (leftHairW + rightHairW) * (1f - guitarW)
        val hairDepth = ((v - 0.18f) / 0.40f).coerceIn(0f, 1f)
        val hairWave = pose.hairSideMid * hairDepth + pose.hairSideTip * hairDepth * hairDepth
        du += hairWave * 0.022f * hairW
        dv -= abs(hairWave) * 0.004f * hairW * hairDepth

        val fringeW = gaussian2d(u, v, 0.36f, 0.125f, 0.11f, 0.06f)
        du += pose.hairFront * 0.010f * fringeW

        // Protect the blue double-cut guitar from the body and hair deformers.
        if (guitarW > 0f) {
            val guitarX = (u - 0.39f) * TEX_ASPECT
            val guitarY = v - 0.48f
            val guitarRad = pose.guitarRock * 0.040f * guitarW
            val cosGuitar = cos(guitarRad)
            val sinGuitar = sin(guitarRad)
            du += (guitarX * cosGuitar - guitarY * sinGuitar - guitarX) * INV_ASPECT
            dv += guitarX * sinGuitar + guitarY * cosGuitar - guitarY
        }

        val strumW = gaussian2d(u, v, 0.35f, 0.53f, 0.06f, 0.05f)
        du += pose.armStrum * 0.007f * strumW
        dv += pose.armStrum * 0.012f * strumW

        val skirtW = gaussian2d(u, v, 0.40f, 0.68f, 0.19f, 0.075f) * (1f - guitarW)
        du += pose.skirtSway * 0.016f * skirtW * smoothstep(0.61f, 0.76f, v)

        return (u + du) to (v + dv)
    }

    fun fillBodyMesh(
        pose: KitaPose,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray
    ) {
        var index = 0
        for (row in 0..MESH_ROWS) {
            val v = row.toFloat() / MESH_ROWS
            for (col in 0..MESH_COLS) {
                val u = col.toFloat() / MESH_COLS
                val (deformedU, deformedV) = deformUv(u, v, pose)
                outVerts[index++] = left + deformedU * width
                outVerts[index++] = top + deformedV * height
            }
        }
    }

    private fun guitarWeight(u: Float, v: Float): Float {
        val guitarDx = (GUITAR_BX - GUITAR_AX) * TEX_ASPECT
        val guitarDy = GUITAR_BY - GUITAR_AY
        val lengthSq = guitarDx * guitarDx + guitarDy * guitarDy
        val progress = (((u - GUITAR_AX) * TEX_ASPECT * guitarDx +
            (v - GUITAR_AY) * guitarDy) / lengthSq).coerceIn(0f, 1f)
        val projectedU = GUITAR_AX + progress * (GUITAR_BX - GUITAR_AX)
        val projectedV = GUITAR_AY + progress * (GUITAR_BY - GUITAR_AY)
        val distance = hypot((u - projectedU) * TEX_ASPECT, v - projectedV)
        val radius = 0.080f + (1f - smoothstep(0.12f, 0.34f, progress)) * 0.075f
        val neckWeight = 1f - smoothstep(radius * 0.82f, radius * 1.50f, distance)
        val bodyWeight = gaussian2d(u, v, 0.24f, 0.55f, 0.18f, 0.14f)
        return max(neckWeight, bodyWeight * 0.90f)
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val progress = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return progress * progress * (3f - 2f * progress)
    }

    private fun gaussian2d(u: Float, v: Float, centerU: Float, centerV: Float, radiusU: Float, radiusV: Float): Float {
        val dx = (u - centerU) / radiusU
        val dy = (v - centerV) / radiusV
        val distanceSq = dx * dx + dy * dy
        if (distanceSq > 9f) {
            return 0f
        }
        return exp(-1.5f * distanceSq)
    }
}

/** 呼吸、視線、瞬き、サイドテール、スカート、ギターを時間ステップで動かす。 */
internal class KitaPhysicsEngine {

    private var timeSec = 0f
    private var scrollImpulse = 0f
    private var strumEnergy = 0f
    private var sparkleTimer = 0f
    private var grooveTimer = 0f
    private var targetGazeX = 0f
    private var targetGazeY = 0f
    private var baseExpression = KitaExpression.CHEERFUL
    private var nextBlinkSec = FIRST_BLINK_SEC
    private var blinkCount = 0

    private val fringeSpring = DampedSpring(omega = 12f, zeta = 0.28f)
    private val hairMidSpring = DampedSpring(omega = 9f, zeta = 0.24f)
    private val hairTipSpring = DampedSpring(omega = 7f, zeta = 0.22f)
    private val ponytailSpring = DampedSpring(omega = 8.5f, zeta = 0.21f)
    private val skirtSpring = DampedSpring(omega = 8f, zeta = 0.30f)

    fun onScroll(deltaPx: Float, viewWidth: Float) {
        if (viewWidth <= 0f) {
            return
        }
        val normalized = (deltaPx / viewWidth).coerceIn(-1f, 1f)
        scrollImpulse = (scrollImpulse + normalized * SCROLL_GAIN).coerceIn(-1.4f, 1.4f)
        targetGazeX = (-normalized * 3f).coerceIn(-1f, 1f)
        strumEnergy = min(1f, strumEnergy + abs(normalized) * 2.2f)
        if (abs(normalized) > FAST_SCROLL_THRESHOLD) {
            grooveTimer = max(grooveTimer, GROOVE_HOLD_SEC)
        }
    }

    fun onSectionChange(section: HomeSection) {
        baseExpression = KitaLive2dPolicy.sectionExpression(section)
        strumEnergy = max(strumEnergy, 0.45f)
    }

    fun onTapZone(zone: KitaHitZone, normX: Float, normY: Float) {
        targetGazeX = ((normX - 0.5f) * 2f).coerceIn(-1f, 1f)
        targetGazeY = ((normY - 0.25f) * 2f).coerceIn(-1f, 1f)
        when (zone) {
            KitaHitZone.HEAD -> {
                sparkleTimer = SPARKLE_HOLD_SEC
                ponytailSpring.addImpulse(2.2f)
                fringeSpring.addImpulse(-1.1f)
            }
            KitaHitZone.GUITAR -> {
                grooveTimer = GROOVE_HOLD_SEC
                strumEnergy = 1f
                hairMidSpring.addImpulse(1.2f)
            }
            KitaHitZone.STAGE -> {
                strumEnergy = max(strumEnergy, 0.60f)
                ponytailSpring.addImpulse(0.9f)
            }
        }
    }

    fun step(dtSec: Float): KitaPose {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        timeSec += dt
        sparkleTimer = max(0f, sparkleTimer - dt)
        grooveTimer = max(0f, grooveTimer - dt)
        strumEnergy = max(0f, strumEnergy - dt * STRUM_DECAY)
        scrollImpulse *= exp(-dt * SCROLL_DECAY)
        targetGazeX *= exp(-dt * GAZE_DECAY)
        targetGazeY *= exp(-dt * GAZE_DECAY)

        val beatPhase = timeSec * (KitaLive2dPolicy.STAGE_BPM / 60f) * 2f * PI.toFloat()
        val beatBounce = -abs(sin(beatPhase * 0.5f)) * (0.18f + 0.82f * strumEnergy)
        val breath = 0.5f + 0.5f * sin(timeSec * BREATH_RAD_PER_SEC)
        val idleYaw = 0.22f * sin(timeSec * 1.05f) + 0.10f * sin(timeSec * 2.25f)
        val idlePitch = 0.17f * sin(timeSec * 1.55f + 0.5f)
        val idleRoll = 0.20f * sin(timeSec * 0.85f + 0.3f)
        val sparkleNod = if (sparkleTimer > 0f) sin(timeSec * 10f) * 0.16f else 0f

        val angleX = (idleYaw + targetGazeX * 0.48f - scrollImpulse * 0.52f).coerceIn(-1f, 1f)
        val angleY = (idlePitch + targetGazeY * 0.36f).coerceIn(-1f, 1f)
        val angleZ = (idleRoll - scrollImpulse * 0.42f + sparkleNod).coerceIn(-1f, 1f)
        val bodyAngleX = (0.19f * sin(timeSec * 0.92f) - scrollImpulse * 0.34f).coerceIn(-1f, 1f)
        val bodyAngleZ = (0.16f * sin(timeSec * 0.92f + 0.4f) - scrollImpulse * 0.28f).coerceIn(-1f, 1f)

        val headDrive = -angleX * 0.75f - angleZ * 0.55f + scrollImpulse * 0.82f
        val hairFront = fringeSpring.step(headDrive, dt)
        val hairMid = hairMidSpring.step(headDrive * 0.95f - bodyAngleX * 0.42f, dt)
        val hairTip = hairTipSpring.step(hairMid * 1.18f, dt)
        val sidePonytail = ponytailSpring.step(headDrive * 1.05f + beatBounce * 0.30f, dt)
        val skirtSway = skirtSpring.step(-bodyAngleX * 0.92f + scrollImpulse * 0.68f, dt)
        val guitarRock = (0.22f * sin(beatPhase * 0.5f + 0.45f) +
            strumEnergy * 0.52f * sin(beatPhase) + scrollImpulse * 0.28f).coerceIn(-1f, 1f)
        val armStrum = ((0.25f + 0.75f * strumEnergy) * sin(beatPhase * 2f)).coerceIn(-1f, 1f)
        val eyeOpen = computeEyeOpen(timeSec)
        val baseMood = when {
            sparkleTimer > 0f -> KitaExpression.SPARKLE
            grooveTimer > 0f -> KitaExpression.PERFORMANCE
            else -> baseExpression
        }
        val expression = KitaLive2dPolicy.activeExpression(baseMood, eyeOpen)

        return KitaPose(
            angleX = angleX,
            angleY = angleY,
            angleZ = angleZ,
            bodyAngleX = bodyAngleX,
            bodyAngleZ = bodyAngleZ,
            breath = breath,
            beatBounce = beatBounce,
            eyeOpen = eyeOpen,
            eyeBallX = (targetGazeX + idleYaw * 0.45f).coerceIn(-1f, 1f),
            eyeBallY = (targetGazeY + idlePitch * 0.35f).coerceIn(-1f, 1f),
            hairFront = hairFront,
            hairSideMid = hairMid,
            hairSideTip = hairTip,
            sidePonytail = sidePonytail,
            guitarRock = guitarRock,
            armStrum = armStrum,
            skirtSway = skirtSway,
            laserTilt = (-scrollImpulse * 0.65f).coerceIn(-1f, 1f),
            expression = expression
        )
    }

    private fun computeEyeOpen(now: Float): Float {
        val elapsed = now - nextBlinkSec
        if (elapsed < 0f) {
            return 1f
        }
        val duration = if (blinkCount % 3 == 1) DOUBLE_BLINK_DURATION else SINGLE_BLINK_DURATION
        if (elapsed >= duration) {
            blinkCount++
            nextBlinkSec = now + if (blinkCount % 2 == 0) 3.2f else 4.3f
            return 1f
        }
        val localTime = elapsed % SINGLE_BLINK_DURATION
        val halfDuration = SINGLE_BLINK_DURATION * 0.5f
        return (abs(localTime - halfDuration) / halfDuration).coerceIn(0f, 1f)
    }

    private class DampedSpring(
        private val omega: Float,
        private val zeta: Float
    ) {
        private var position = 0f
        private var velocity = 0f

        fun addImpulse(impulse: Float) {
            velocity = (velocity + impulse).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
        }

        fun step(target: Float, dt: Float): Float {
            val force = omega * omega * (target - position) - 2f * zeta * omega * velocity
            velocity += force * dt
            position = (position + velocity * dt).coerceIn(-1f, 1f)
            return position
        }
    }

    private companion object {
        const val MIN_DT = 0.001f
        const val MAX_DT = 0.050f
        const val BREATH_RAD_PER_SEC = 1.75f
        const val SCROLL_GAIN = 3.8f
        const val SCROLL_DECAY = 5.1f
        const val GAZE_DECAY = 2.6f
        const val STRUM_DECAY = 1.35f
        const val FAST_SCROLL_THRESHOLD = 0.035f
        const val GROOVE_HOLD_SEC = 2.0f
        const val SPARKLE_HOLD_SEC = 0.8f
        const val FIRST_BLINK_SEC = 1.8f
        const val SINGLE_BLINK_DURATION = 0.20f
        const val DOUBLE_BLINK_DURATION = 0.40f
        const val MAX_VELOCITY = 12f
    }
}
