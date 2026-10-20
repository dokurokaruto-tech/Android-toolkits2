package com.example.kennys_dokidoki_wallpaper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 後藤ひとり Live2D 変形・物理演算・表情ステートマシン。
 *
 *   [Scroll / Tap / 150BPM Beat]
 *                |
 *                v
 *   +-------------------------+      +---------------------------+
 *   |   BocchiPhysicsEngine   | ---> |        BocchiPose         |
 *   |  - 6 Pendulum Chains    |      |  - Head 2.5D (X, Y, Z)    |
 *   |  - Blink State Machine  |      |  - Hair / Cube / Ahoge    |
 *   |  - Guitar Strum Spring  |      |  - Guitar Rock & Strum    |
 *   +-------------------------+      +---------------------------+
 *                                                  |
 *                                                  v
 *                                    +---------------------------+
 *                                    |   BocchiLive2dPolicy      |
 *                                    |  - 28x36 Body ArtMesh     |
 *                                    |  - 10x8 Face Sub-Mesh     |
 *                                    +---------------------------+
 */
enum class BocchiExpression(val assetName: String?) {
    SHY_BASE(null),
    HALF_BLINK("live2d/bocchi/bocchi_face_half_blink.webp"),
    BLINK_CLOSED("live2d/bocchi/bocchi_face_blink.webp"),
    HAPPY_SMILE("live2d/bocchi/bocchi_face_smile.webp"),
    AWAKENED_GROOVE("live2d/bocchi/bocchi_face_awakened.webp"),
    BOCCHI_PANIC("live2d/bocchi/bocchi_face_panic.webp")
}

enum class BocchiHitZone {
    HEAD_PANIC,
    GUITAR_STRUM,
    STAGE_BURST
}

data class BocchiPose(
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
    val ahoge: Float = 0f,
    val cubeSwing: Float = 0f,
    val guitarRock: Float = 0f,
    val armStrum: Float = 0f,
    val skirtSway: Float = 0f,
    val glitchIntensity: Float = 0f,
    val laserTilt: Float = 0f,
    val expression: BocchiExpression = BocchiExpression.SHY_BASE
)

object BocchiLive2dPolicy {

    const val MESH_COLS = 28
    const val MESH_ROWS = 36
    const val FACE_COLS = 10
    const val FACE_ROWS = 8

    const val BODY_ASSET = "live2d/bocchi/bocchi_body_base.webp"

    // 切り出し済みボディ (592x750) に対する顔パッチ (160,90,156x126) の正規化UV
    const val FACE_U0 = 160f / 592f
    const val FACE_V0 = 90f / 750f
    const val FACE_U1 = 316f / 592f
    const val FACE_V1 = 216f / 750f

    // 瞳ハイライト・髪飾り・ギター弦の基準UV座標
    const val EYE_LEFT_U = 0.352f
    const val EYE_LEFT_V = 0.203f
    const val EYE_RIGHT_U = 0.453f
    const val EYE_RIGHT_V = 0.205f
    const val CUBE_BLUE_U = 0.250f
    const val CUBE_BLUE_V = 0.125f
    const val CUBE_YELLOW_U = 0.248f
    const val CUBE_YELLOW_V = 0.165f
    const val GUITAR_BRIDGE_U = 0.310f
    const val GUITAR_BRIDGE_V = 0.625f
    const val GUITAR_NUT_U = 0.855f
    const val GUITAR_NUT_V = 0.395f

    const val STAGE_BPM = 150f

    private const val TEX_ASPECT = 592f / 750f
    private const val INV_ASPECT = 750f / 592f
    private const val GUITAR_AX = 0.26f
    private const val GUITAR_AY = 0.65f
    private const val GUITAR_BX = 0.96f
    private const val GUITAR_BY = 0.35f
    private const val HEAD_PIVOT_U = 0.40f
    private const val HEAD_PIVOT_V = 0.285f
    private const val GUITAR_PIVOT_U = 0.38f
    private const val GUITAR_PIVOT_V = 0.58f
    private const val BODY_PIVOT_U = 0.40f
    private const val BODY_PIVOT_V = 0.76f
    private const val HALF_BLINK_THRESHOLD = 0.68f
    private const val CLOSED_BLINK_THRESHOLD = 0.25f

    /** タップ座標をキャラクターの正規化UVへ変換し、反応部位を判定する */
    fun hitZone(u: Float, v: Float): BocchiHitZone {
        if (u in 0.20f..0.58f && v in 0.04f..0.30f) {
            return BocchiHitZone.HEAD_PANIC
        }
        if (u in 0.12f..0.92f && v in 0.32f..0.76f) {
            return BocchiHitZone.GUITAR_STRUM
        }
        return BocchiHitZone.STAGE_BURST
    }

    /** 機能セクションに応じたベース表情を返す */
    fun sectionExpression(section: HomeSection): BocchiExpression = when (section) {
        HomeSection.BUILDER, HomeSection.TAGS -> BocchiExpression.AWAKENED_GROOVE
        HomeSection.DIARY, HomeSection.IMAGE_SETS -> BocchiExpression.HAPPY_SMILE
        HomeSection.ALL_IMAGES, HomeSection.SETTINGS -> BocchiExpression.SHY_BASE
    }

    /** 瞬き開度と感情モードから、現在描画すべき顔パッチを決める */
    fun activeExpression(base: BocchiExpression, eyeOpen: Float): BocchiExpression {
        if (base == BocchiExpression.BOCCHI_PANIC) {
            return BocchiExpression.BOCCHI_PANIC
        }
        if (eyeOpen <= CLOSED_BLINK_THRESHOLD) {
            return BocchiExpression.BLINK_CLOSED
        }
        if (eyeOpen <= HALF_BLINK_THRESHOLD) {
            return BocchiExpression.HALF_BLINK
        }
        return base
    }

    /** 正規化UV (0..1) に対して階層デフォーマ変形を適用した座標を返す */
    fun deformUv(u: Float, v: Float, pose: BocchiPose): Pair<Float, Float> {
        var du = 0f
        var dv = 0f

        val guitarW = guitarWeight(u, v)

        // 1. 上半身の揺れと150BPMビートバウンス
        val upperW = 1f - smoothstep(0.74f, 0.90f, v)
        if (upperW > 0f) {
            val relX = (u - BODY_PIVOT_U) * TEX_ASPECT
            val relY = v - BODY_PIVOT_V
            val bodyRad = pose.bodyAngleZ * 0.042f * upperW
            val cosB = cos(bodyRad)
            val sinB = sin(bodyRad)
            du += ((relX * cosB - relY * sinB - relX) * INV_ASPECT) + pose.bodyAngleX * 0.018f * upperW
            dv += (relX * sinB + relY * cosB - relY) + pose.beatBounce * 0.010f * upperW
        }

        // 2. 胸・肩の呼吸デフォーマ（ギター領域は除外して歪みを防ぐ）
        val breathW = gaussian2d(u, v, 0.40f, 0.37f, 0.16f, 0.10f) * (1f - guitarW)
        du += (u - 0.40f) * 0.024f * pose.breath * breathW
        dv -= 0.009f * pose.breath * breathW

        // 3. 頭部 2.5D 回転＋パース移動デフォーマ
        val headW = (1f - smoothstep(0.25f, 0.32f, v)) *
            smoothstep(0.12f, 0.24f, u) *
            (1f - smoothstep(0.54f, 0.65f, u))
        if (headW > 0f) {
            val rx = (u - HEAD_PIVOT_U) * TEX_ASPECT
            val ry = v - HEAD_PIVOT_V
            val headRad = pose.angleZ * 0.105f * headW
            val cosH = cos(headRad)
            val sinH = sin(headRad)
            du += (rx * cosH - ry * sinH - rx) * INV_ASPECT
            dv += (rx * sinH + ry * cosH - ry)

            val faceCenterW = gaussian2d(u, v, 0.40f, 0.20f, 0.13f, 0.09f)
            du += pose.angleX * (0.015f * headW + 0.011f * faceCenterW)
            dv += pose.angleY * (0.011f * headW + 0.007f * faceCenterW)
        }

        // 4. アホ毛のスプリング揺れ
        val ahogeW = gaussian2d(u, v, 0.46f, 0.03f, 0.06f, 0.04f) * (1f - smoothstep(0.04f, 0.07f, v))
        du += pose.ahoge * 0.028f * ahogeW
        dv += abs(pose.ahoge) * 0.008f * ahogeW

        // 5. 青と黄色のキューブ髪飾り＋サイド結びの揺れ
        val cubeW = gaussian2d(u, v, 0.21f, 0.16f, 0.08f, 0.06f)
        val tuftBoost = ((0.26f - u) * 8f).coerceIn(0f, 1.5f)
        du += pose.cubeSwing * 0.018f * cubeW
        dv += pose.cubeSwing * 0.022f * cubeW * tuftBoost

        // 6. 前髪の揺れ
        val bangsW = gaussian2d(u, v, 0.40f, 0.15f, 0.09f, 0.05f)
        du += pose.hairFront * 0.014f * bangsW

        // 7. レスポール・カスタムの剛体回転デフォーマ
        if (guitarW > 0f) {
            val gx = (u - GUITAR_PIVOT_U) * TEX_ASPECT
            val gy = v - GUITAR_PIVOT_V
            val gRad = pose.guitarRock * 0.048f * guitarW
            val cosG = cos(gRad)
            val sinG = sin(gRad)
            du += (gx * cosG - gy * sinG - gx) * INV_ASPECT
            dv += (gx * sinG + gy * cosG - gy)
        }

        // 8. 左右ロングヘア毛束の2段振り子ウェーブ（ギター背面のみ）
        val leftHairW = (1f - smoothstep(0.18f, 0.28f, u)) *
            smoothstep(0.16f, 0.26f, v) *
            (1f - smoothstep(0.54f, 0.63f, v))
        val rightHairW = smoothstep(0.50f, 0.57f, u) *
            (1f - smoothstep(0.64f, 0.72f, u)) *
            smoothstep(0.16f, 0.26f, v) *
            (1f - smoothstep(0.56f, 0.65f, v))
        val hairFactor = (leftHairW + rightHairW) * (1f - guitarW)
        if (hairFactor > 0f) {
            val depth = ((v - 0.18f) / 0.44f).coerceIn(0f, 1f)
            val wave = pose.hairSideMid * depth + pose.hairSideTip * depth * depth
            du += wave * 0.028f * hairFactor
            dv -= abs(wave) * 0.005f * hairFactor * depth
        }

        // 9. 右手ストロークの往復デフォーマ
        val strumW = gaussian2d(u, v, 0.335f, 0.615f, 0.055f, 0.048f)
        du += pose.armStrum * 0.010f * strumW
        dv += pose.armStrum * 0.018f * strumW

        // 10. プリーツスカートの揺れ
        val skirtW = gaussian2d(u, v, 0.42f, 0.74f, 0.18f, 0.06f) * (1f - guitarW)
        du += pose.skirtSway * 0.020f * skirtW * smoothstep(0.66f, 0.80f, v)

        return (u + du) to (v + dv)
    }

    /** ボディ全体の drawBitmapMesh 頂点配列を更新する */
    fun fillBodyMesh(
        pose: BocchiPose,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray
    ) {
        var idx = 0
        for (row in 0..MESH_ROWS) {
            val v = row.toFloat() / MESH_ROWS
            for (col in 0..MESH_COLS) {
                val u = col.toFloat() / MESH_COLS
                val (du, dv) = deformUv(u, v, pose)
                outVerts[idx++] = left + du * width
                outVerts[idx++] = top + dv * height
            }
        }
    }

    /** 顔パッチの drawBitmapMesh 頂点配列を、ボディと同一の変形関数で更新する */
    fun fillFaceMesh(
        pose: BocchiPose,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray
    ) {
        var idx = 0
        val spanU = FACE_U1 - FACE_U0
        val spanV = FACE_V1 - FACE_V0
        for (row in 0..FACE_ROWS) {
            val v = FACE_V0 + (row.toFloat() / FACE_ROWS) * spanV
            for (col in 0..FACE_COLS) {
                val u = FACE_U0 + (col.toFloat() / FACE_COLS) * spanU
                val (du, dv) = deformUv(u, v, pose)
                outVerts[idx++] = left + du * width
                outVerts[idx++] = top + dv * height
            }
        }
    }

    private fun guitarWeight(u: Float, v: Float): Float {
        val gDx = (GUITAR_BX - GUITAR_AX) * TEX_ASPECT
        val gDy = GUITAR_BY - GUITAR_AY
        val lenSq = gDx * gDx + gDy * gDy
        val t = (((u - GUITAR_AX) * TEX_ASPECT * gDx + (v - GUITAR_AY) * gDy) / lenSq).coerceIn(0f, 1f)
        val projU = GUITAR_AX + t * (GUITAR_BX - GUITAR_AX)
        val projV = GUITAR_AY + t * (GUITAR_BY - GUITAR_AY)
        val dist = hypot((u - projU) * TEX_ASPECT, v - projV)
        val radius = (1f - smoothstep(0.20f, 0.50f, t)) * 0.11f + 0.085f
        return 1f - smoothstep(radius * 0.85f, radius * 1.55f, dist)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun gaussian2d(u: Float, v: Float, cu: Float, cv: Float, ru: Float, rv: Float): Float {
        val du = (u - cu) / ru
        val dv = (v - cv) / rv
        val d2 = du * du + dv * dv
        if (d2 > 9f) {
            return 0f
        }
        return exp(-1.5f * d2)
    }
}

/**
 * Live2D 物理演算（減衰バネ振り子チェーン＋自動瞬き＋ビート同期）を時間ステップで進めるエンジン。
 */
class BocchiPhysicsEngine {

    private var timeSec = 0f
    private var scrollImpulse = 0f
    private var strumEnergy = 0f
    private var panicTimer = 0f
    private var grooveTimer = 0f
    private var targetGazeX = 0f
    private var targetGazeY = 0f
    private var baseExpression = BocchiExpression.SHY_BASE

    // 瞬きタイマー（自然な単発・2連瞬きを交互に発生させる）
    private var nextBlinkSec = FIRST_BLINK_SEC
    private var blinkCount = 0

    // 6系統の減衰バネ振り子
    private val hairFrontSpring = DampedSpring(omega = 12.5f, zeta = 0.26f)
    private val hairMidSpring = DampedSpring(omega = 9.5f, zeta = 0.24f)
    private val hairTipSpring = DampedSpring(omega = 7.2f, zeta = 0.20f)
    private val ahogeSpring = DampedSpring(omega = 18.0f, zeta = 0.18f)
    private val cubeSpring = DampedSpring(omega = 14.0f, zeta = 0.22f)
    private val skirtSpring = DampedSpring(omega = 8.2f, zeta = 0.30f)

    fun onScroll(deltaPx: Float, viewWidth: Float) {
        if (viewWidth <= 0f) {
            return
        }
        val norm = (deltaPx / viewWidth).coerceIn(-1f, 1f)
        scrollImpulse = (scrollImpulse + norm * SCROLL_GAIN).coerceIn(-1.5f, 1.5f)
        targetGazeX = (-norm * 3.2f).coerceIn(-1f, 1f)
        strumEnergy = min(1f, strumEnergy + abs(norm) * 2.4f)
        if (abs(norm) > FAST_SCROLL_THRESHOLD) {
            grooveTimer = max(grooveTimer, GROOVE_HOLD_SEC)
        }
    }

    fun onSectionChange(section: HomeSection) {
        baseExpression = BocchiLive2dPolicy.sectionExpression(section)
        strumEnergy = max(strumEnergy, 0.55f)
    }

    fun onTapZone(zone: BocchiHitZone, normX: Float, normY: Float) {
        targetGazeX = ((normX - 0.5f) * 2f).coerceIn(-1f, 1f)
        targetGazeY = ((normY - 0.35f) * 2f).coerceIn(-1f, 1f)
        when (zone) {
            BocchiHitZone.HEAD_PANIC -> {
                panicTimer = PANIC_HOLD_SEC
                ahogeSpring.addImpulse(2.4f)
                cubeSpring.addImpulse(-2.0f)
            }

            BocchiHitZone.GUITAR_STRUM -> {
                grooveTimer = GROOVE_HOLD_SEC
                strumEnergy = 1f
                hairMidSpring.addImpulse(1.4f)
            }

            BocchiHitZone.STAGE_BURST -> {
                strumEnergy = max(strumEnergy, 0.65f)
                ahogeSpring.addImpulse(1.1f)
            }
        }
    }

    fun step(dtSec: Float): BocchiPose {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        timeSec += dt

        // タイマー減衰
        panicTimer = max(0f, panicTimer - dt)
        grooveTimer = max(0f, grooveTimer - dt)
        strumEnergy = max(0f, strumEnergy - dt * STRUM_DECAY)
        scrollImpulse *= exp(-dt * SCROLL_DECAY)
        targetGazeX *= exp(-dt * GAZE_DECAY)
        targetGazeY *= exp(-dt * GAZE_DECAY)

        // 150 BPM ビート周期 (2.5 Hz)
        val beatHz = BocchiLive2dPolicy.STAGE_BPM / 60f
        val beatPhase = timeSec * beatHz * 2f * PI.toFloat()
        val beatBounce = -abs(sin(beatPhase * 0.5f)) * (0.45f + 0.55f * strumEnergy)

        // 呼吸と自発的な首・体幹の揺れ
        val breath = 0.5f + 0.5f * sin(timeSec * BREATH_RAD_PER_SEC)
        val idleYaw = 0.32f * sin(timeSec * 1.15f) + 0.14f * sin(timeSec * 2.45f)
        val idlePitch = 0.22f * sin(timeSec * 1.65f + 0.8f)
        val idleRoll = 0.38f * sin(timeSec * 0.95f + 0.4f)

        val panicJitter = if (panicTimer > 0f) {
            sin(timeSec * 48f) * 0.35f
        } else {
            0f
        }

        val angleX = (idleYaw + targetGazeX * 0.55f - scrollImpulse * 0.65f + panicJitter).coerceIn(-1f, 1f)
        val angleY = (idlePitch + targetGazeY * 0.45f).coerceIn(-1f, 1f)
        val angleZ = (idleRoll - scrollImpulse * 0.55f + panicJitter * 0.8f).coerceIn(-1f, 1f)
        val bodyAngleX = (0.25f * sin(timeSec * 0.95f) - scrollImpulse * 0.45f).coerceIn(-1f, 1f)
        val bodyAngleZ = (0.22f * sin(timeSec * 0.95f + 0.3f) - scrollImpulse * 0.35f).coerceIn(-1f, 1f)

        // 頭部・体幹の運動を6系統の物理バネ振り子へ入力
        val headDrive = -angleX * 0.85f - angleZ * 0.65f + scrollImpulse * 0.9f
        val hairFront = hairFrontSpring.step(headDrive, dt)
        val hairMid = hairMidSpring.step(headDrive * 1.1f - bodyAngleX * 0.5f, dt)
        val hairTip = hairTipSpring.step(hairMid * 1.25f, dt)
        val ahoge = ahogeSpring.step(headDrive * 1.3f + beatBounce * 0.6f, dt)
        val cubeSwing = cubeSpring.step(headDrive * 1.15f + beatBounce * 0.5f, dt)
        val skirtSway = skirtSpring.step(-bodyAngleX * 1.1f + scrollImpulse * 0.8f, dt)

        // ギターのネック揺れと右手ストローク
        val guitarRock = (0.28f * sin(beatPhase * 0.5f + 0.6f) +
            strumEnergy * 0.62f * sin(beatPhase) +
            scrollImpulse * 0.35f).coerceIn(-1f, 1f)
        val armStrum = ((0.22f + 0.78f * strumEnergy) * sin(beatPhase * 2f)).coerceIn(-1f, 1f)

        val eyeOpen = computeEyeOpen(timeSec)
        val modeExpr = when {
            panicTimer > 0f -> BocchiExpression.BOCCHI_PANIC
            grooveTimer > 0f -> BocchiExpression.AWAKENED_GROOVE
            else -> baseExpression
        }
        val finalExpr = BocchiLive2dPolicy.activeExpression(modeExpr, eyeOpen)
        val glitch = if (panicTimer > 0f) {
            (panicTimer / PANIC_HOLD_SEC).coerceIn(0f, 1f)
        } else {
            0f
        }

        return BocchiPose(
            angleX = angleX,
            angleY = angleY,
            angleZ = angleZ,
            bodyAngleX = bodyAngleX,
            bodyAngleZ = bodyAngleZ,
            breath = breath,
            beatBounce = beatBounce,
            eyeOpen = eyeOpen,
            eyeBallX = (targetGazeX + idleYaw * 0.5f).coerceIn(-1f, 1f),
            eyeBallY = (targetGazeY + idlePitch * 0.4f).coerceIn(-1f, 1f),
            hairFront = hairFront,
            hairSideMid = hairMid,
            hairSideTip = hairTip,
            ahoge = ahoge,
            cubeSwing = cubeSwing,
            guitarRock = guitarRock,
            armStrum = armStrum,
            skirtSway = skirtSway,
            glitchIntensity = glitch,
            laserTilt = (-scrollImpulse * 0.75f).coerceIn(-1f, 1f),
            expression = finalExpr
        )
    }

    private fun computeEyeOpen(now: Float): Float {
        val elapsed = now - nextBlinkSec
        if (elapsed < 0f) {
            return 1f
        }
        val duration = if (blinkCount % 3 == 1) {
            DOUBLE_BLINK_DURATION
        } else {
            SINGLE_BLINK_DURATION
        }
        if (elapsed >= duration) {
            blinkCount++
            val interval = if (blinkCount % 2 == 0) {
                3.1f
            } else {
                4.2f
            }
            nextBlinkSec = now + interval
            return 1f
        }
        val local = elapsed % SINGLE_BLINK_DURATION
        val half = SINGLE_BLINK_DURATION * 0.5f
        return (abs(local - half) / half).coerceIn(0f, 1f)
    }

    private class DampedSpring(
        private val omega: Float,
        private val zeta: Float
    ) {
        private var pos = 0f
        private var vel = 0f

        fun addImpulse(impulse: Float) {
            vel = (vel + impulse).coerceIn(-MAX_VEL, MAX_VEL)
        }

        fun step(target: Float, dt: Float): Float {
            val force = omega * omega * (target - pos) - 2f * zeta * omega * vel
            vel += force * dt
            pos = (pos + vel * dt).coerceIn(-1f, 1f)
            return pos
        }
    }

    private companion object {
        const val MIN_DT = 0.001f
        const val MAX_DT = 0.050f
        const val BREATH_RAD_PER_SEC = 1.85f
        const val SCROLL_GAIN = 4.2f
        const val SCROLL_DECAY = 5.5f
        const val GAZE_DECAY = 2.8f
        const val STRUM_DECAY = 1.4f
        const val FAST_SCROLL_THRESHOLD = 0.035f
        const val GROOVE_HOLD_SEC = 2.2f
        const val PANIC_HOLD_SEC = 1.8f
        const val FIRST_BLINK_SEC = 1.6f
        const val SINGLE_BLINK_DURATION = 0.22f
        const val DOUBLE_BLINK_DURATION = 0.44f
        const val MAX_VEL = 12f
    }
}
