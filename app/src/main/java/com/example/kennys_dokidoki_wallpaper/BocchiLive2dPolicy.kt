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
 * 結束バンドの4人の2Dメッシュアニメーション。
 *
 *   [Scroll / Tap / スクロール / 150BPM]
 *                |
 *                v
 *   +-------------------------+      +---------------------------+
 *   |   BocchiPhysicsEngine   | ---> |        BocchiPose         |
 *   |  - 6 Pendulum Chains    |      |  - Head 2.5D (X, Y, Z)    |
 *   |  - Blink State Machine  |      |  - Hair / Accessory       |
 *   |  - Expression Scheduler |      |  - Instrument / Limbs     |
 *   |  - Drum Strike Clock    |      |  - Drum Arm / Pedal       |
 *   +-------------------------+      +---------------------------+
 *                                                  |
 *                                                  v
 *                                    +---------------------------+
 *                                    |      CharacterRig         |
 *                                    |  - 28x36 Body ArtMesh     |
 *                                    |  - Face Sub-Mesh per char |
 *                                    +---------------------------+
 */
enum class BocchiExpression(val assetName: String?) {
    SHY_BASE(null),
    HALF_BLINK("live2d/bocchi/bocchi_face_half_blink.webp"),
    BLINK_CLOSED("live2d/bocchi/bocchi_face_blink.webp"),
    HAPPY_SMILE("live2d/bocchi/bocchi_face_smile.webp"),
    AWAKENED_GROOVE("live2d/bocchi/bocchi_face_awakened.webp"),
    BOCCHI_PANIC("live2d/bocchi/bocchi_face_panic.webp"),

    /** 目を閉じて笑う。喜多・虹夏の定番。 */
    LAUGH_JOY(null),
    /** 見開き。驚きと不意打ち。 */
    SURPRISED(null),
    /** ウインク。 */
    WINK(null)
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
    /** 振り上げたスティックの腕。0 で打ち下ろし位置。 */
    val stickArm: Float = 0f,
    /** スネアを叩く腕。 */
    val snareArm: Float = 0f,
    /** バスドラのペダル。 */
    val kickPedal: Float = 0f,
    /** ドラム全体の微振動。 */
    val kitShake: Float = 0f,
    /** 直前のビートで叩いた打面。0 なら打撃なし。 */
    val drumHit: DrumPad = DrumPad.NONE,
    val expression: BocchiExpression = BocchiExpression.SHY_BASE
)

/** ドラムの打面。ヒットエフェクトの発生源。 */
enum class DrumPad { NONE, SNARE, HI_HAT, KICK, CRASH }

object BocchiLive2dPolicy {

    const val MESH_COLS = 28
    const val MESH_ROWS = 36
    const val FACE_COLS = 10
    const val FACE_ROWS = 8

    const val BODY_ASSET = "live2d/bocchi/bocchi_body_base.webp"

    // 後藤ひとりのリグ値。既存互換のため公開定数として残す。
    const val FACE_U0 = 160f / 592f
    const val FACE_V0 = 90f / 750f
    const val FACE_U1 = 316f / 592f
    const val FACE_V1 = 216f / 750f

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
    private const val HALF_BLINK_THRESHOLD = 0.68f
    private const val CLOSED_BLINK_THRESHOLD = 0.25f
    private const val HEAD_TURN_RAD = 0.105f
    private const val HEAD_PARALLAX = 0.015f
    private const val FACE_PARALLAX = 0.011f
    private const val INSTRUMENT_ROCK_RAD = 0.048f
    private const val LIMB_RAD = 0.16f
    private const val HAIR_SWING = 0.028f
    private const val HAIR_LIFT = 0.005f

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
    fun deformUv(
        u: Float,
        v: Float,
        pose: BocchiPose,
        rig: CharacterRig = CharacterRigs.HITORI
    ): Pair<Float, Float> {
        val instrumentW = instrumentWeight(u, v, rig.instrument)
        val sway = swayMask(u, v, rig)

        val body = bodyShift(u, v, pose, rig, sway)
        val head = headShift(u, v, pose, rig)
        val hair = hairShift(u, v, pose, rig, instrumentW)
        val limb = limbShift(u, v, pose, rig)

        val du = body.first + head.first + hair.first + limb.first
        val dv = body.second + head.second + hair.second + limb.second
        return (u + du) to (v + dv)
    }

    /**
     * 上半身の揺れと150BPMビートバウンス。くるぶし(anchorV)より下は動かさない。
     * swayMask を持つキャラ（座った虹夏）は胴体まわりだけが弾む。
     */
    private fun bodyShift(
        u: Float,
        v: Float,
        pose: BocchiPose,
        rig: CharacterRig,
        sway: Float
    ): Pair<Float, Float> {
        val anchor = rig.torso.anchorV
        val upperW = (1f - smoothstep(anchor - 0.16f, anchor, v)) * sway
        if (upperW <= 0f) {
            return 0f to 0f
        }
        val relX = (u - rig.torso.pivot.u) * TEX_ASPECT
        val relY = v - rig.torso.pivot.v
        val rad = pose.bodyAngleZ * 0.042f * upperW
        val du = (relX * cos(rad) - relY * sin(rad) - relX) * INV_ASPECT +
            pose.bodyAngleX * 0.018f * upperW
        val dv = relX * sin(rad) + relY * cos(rad) - relY + pose.beatBounce * 0.010f * upperW

        // 呼吸は胸まわりを膨らませ、楽器の上では歪ませない。
        val breath = rig.torso.breath
        val breathW = gaussian2d(u, v, breath.center.u, breath.center.v, breath.radiusU, breath.radiusV) *
            (1f - instrumentWeight(u, v, rig.instrument))
        return (du + (u - breath.center.u) * 0.024f * pose.breath * breathW) to
            (dv - 0.009f * pose.breath * breathW)
    }

    private fun swayMask(u: Float, v: Float, rig: CharacterRig): Float {
        val mask = rig.torso.swayMask ?: return 1f
        return gaussian2d(u, v, mask.center.u, mask.center.v, mask.radiusU, mask.radiusV)
    }

    private fun headShift(
        u: Float,
        v: Float,
        pose: BocchiPose,
        rig: CharacterRig
    ): Pair<Float, Float> {
        val head = rig.head
        val k = head.halfWidthU / 0.13f
        val headW = (1f - smoothstep(head.bottomV - 0.07f, head.bottomV, v)) *
            smoothstep(head.pivot.u - 0.28f * k, head.pivot.u - 0.16f * k, u) *
            (1f - smoothstep(head.pivot.u + 0.14f * k, head.pivot.u + 0.25f * k, u))

        var du = 0f
        var dv = 0f
        if (headW > 0f) {
            val rx = (u - head.pivot.u) * TEX_ASPECT
            val ry = v - head.pivot.v
            val headRad = pose.angleZ * HEAD_TURN_RAD * headW
            val cosH = cos(headRad)
            val sinH = sin(headRad)
            du += (rx * cosH - ry * sinH - rx) * INV_ASPECT
            dv += rx * sinH + ry * cosH - ry

            val faceCenterW = gaussian2d(
                u, v, head.pivot.u, head.pivot.v - 0.085f, 0.13f * k, 0.09f
            )
            du += pose.angleX * (HEAD_PARALLAX * headW + FACE_PARALLAX * faceCenterW)
            dv += pose.angleY * (0.011f * headW + 0.007f * faceCenterW)
        }

        // アホ毛のスプリング揺れ
        rig.ahoge?.let { region ->
            val ahogeW = gaussian2d(u, v, region.center.u, region.center.v, region.radiusU, region.radiusV) *
                (1f - smoothstep(region.center.v + 0.01f, region.center.v + 0.04f, v))
            du += pose.ahoge * 0.028f * ahogeW
            dv += abs(pose.ahoge) * 0.008f * ahogeW
        }

        // 髪飾りの揺れ
        rig.accessory?.let { region ->
            val accessoryW = gaussian2d(u, v, region.center.u, region.center.v, region.radiusU, region.radiusV)
            val tuftBoost = ((region.center.u + 0.05f - u) * 8f).coerceIn(0f, 1.5f)
            du += pose.cubeSwing * 0.018f * accessoryW
            dv += pose.cubeSwing * 0.022f * accessoryW * tuftBoost
        }

        // 前髪の揺れ
        rig.bangs?.let { region ->
            du += pose.hairFront * 0.014f *
                gaussian2d(u, v, region.center.u, region.center.v, region.radiusU, region.radiusV)
        }

        return du to dv
    }

    /** 楽器の剛体回転と、弦を弾く手。 */
    private fun instrumentShift(
        u: Float,
        v: Float,
        pose: BocchiPose,
        rig: CharacterRig,
        instrumentW: Float
    ): Pair<Float, Float> {
        val instrument = rig.instrument ?: return 0f to 0f
        var du = 0f
        var dv = 0f
        if (instrumentW > 0f) {
            val gx = (u - instrument.pivot.u) * TEX_ASPECT
            val gy = v - instrument.pivot.v
            val gRad = pose.guitarRock * INSTRUMENT_ROCK_RAD * instrumentW
            du += (gx * cos(gRad) - gy * sin(gRad) - gx) * INV_ASPECT
            dv += gx * sin(gRad) + gy * cos(gRad) - gy
        }
        val handW = gaussian2d(
            u, v, instrument.pluckHand.center.u, instrument.pluckHand.center.v,
            instrument.pluckHand.radiusU, instrument.pluckHand.radiusV
        )
        du += pose.armStrum * 0.010f * handW
        dv += pose.armStrum * 0.018f * handW
        return du to dv
    }

    /** ロングヘアの2段振り子とスカートの揺れ。 */
    private fun hairShift(
        u: Float,
        v: Float,
        pose: BocchiPose,
        rig: CharacterRig,
        instrumentW: Float
    ): Pair<Float, Float> {
        var du = 0f
        var dv = 0f
        val hair = rig.hair
        val leftW = (1f - smoothstep(hair.leftBandU.start, hair.leftBandU.endInclusive, u)) *
            smoothstep(hair.topV, hair.topV + 0.10f, v) *
            (1f - smoothstep(hair.bottomV - 0.11f, hair.bottomV - 0.02f, v))
        val rightW = smoothstep(hair.rightBandU.start, hair.rightBandU.start + 0.07f, u) *
            (1f - smoothstep(hair.rightBandU.endInclusive - 0.08f, hair.rightBandU.endInclusive, u)) *
            smoothstep(hair.topV, hair.topV + 0.10f, v) *
            (1f - smoothstep(hair.bottomV - 0.09f, hair.bottomV, v))
        val factor = (leftW + rightW) * (1f - instrumentW)
        if (factor > 0f) {
            val start = hair.topV + 0.02f
            val depth = ((v - start) / max(0.05f, hair.bottomV - 0.03f - start)).coerceIn(0f, 1f)
            val wave = pose.hairSideMid * depth + pose.hairSideTip * depth * depth
            du += wave * HAIR_SWING * factor
            dv -= abs(wave) * HAIR_LIFT * factor * depth
        }

        rig.skirt?.let { region ->
            val skirtW = gaussian2d(u, v, region.center.u, region.center.v, region.radiusU, region.radiusV) *
                (1f - instrumentW)
            du += pose.skirtSway * 0.020f * skirtW *
                smoothstep(region.center.v - 0.08f, region.center.v + 0.06f, v)
        }

        val instrument = instrumentShift(u, v, pose, rig, instrumentW)
        return (du + instrument.first) to (dv + instrument.second)
    }

    /** 腕・ペダルの回転。ドラム用。 */
    private fun limbShift(u: Float, v: Float, pose: BocchiPose, rig: CharacterRig): Pair<Float, Float> {
        if (rig.limbs.isEmpty()) {
            return 0f to 0f
        }
        var du = 0f
        var dv = 0f
        for (limb in rig.limbs) {
            val angle = when (limb.channel) {
                LimbChannel.STICK_ARM -> pose.stickArm
                LimbChannel.SNARE_ARM -> pose.snareArm
                LimbChannel.PEDAL_FOOT -> pose.kickPedal
            }
            if (angle == 0f) {
                continue
            }
            val w = gaussian2d(
                u, v, limb.region.center.u, limb.region.center.v,
                limb.region.radiusU, limb.region.radiusV
            )
            if (w <= 0.001f) {
                continue
            }
            val rad = angle * LIMB_RAD * w
            val rx = (u - limb.pivot.u) * TEX_ASPECT
            val ry = v - limb.pivot.v
            du += (rx * cos(rad) - ry * sin(rad) - rx) * INV_ASPECT
            dv += rx * sin(rad) + ry * cos(rad) - ry
        }
        // ドラムセットの微振動。
        if (pose.kitShake != 0f && rig.drumPads != null) {
            val kitW = (1f - smoothstep(0.36f, 0.46f, u)) * smoothstep(0.50f, 0.60f, v)
            du += pose.kitShake * 0.004f * kitW
            dv += pose.kitShake * 0.003f * kitW
        }
        return du to dv
    }

    /** 楽器の弦に沿った重み。0 なら楽器の影響なし。 */
    private fun instrumentWeight(u: Float, v: Float, instrument: InstrumentRig?): Float {
        if (instrument == null) {
            return 0f
        }
        return instrumentWeight(u, v, instrument.axisA, instrument.axisB)
    }

    private fun instrumentWeight(u: Float, v: Float, a: RigPoint, b: RigPoint): Float {
        val gDx = (b.u - a.u) * TEX_ASPECT
        val gDy = b.v - a.v
        val lenSq = gDx * gDx + gDy * gDy
        val t = (((u - a.u) * TEX_ASPECT * gDx + (v - a.v) * gDy) / lenSq).coerceIn(0f, 1f)
        val projU = a.u + t * (b.u - a.u)
        val projV = a.v + t * (b.v - a.v)
        val dist = hypot((u - projU) * TEX_ASPECT, v - projV)
        val radius = (1f - smoothstep(0.20f, 0.50f, t)) * 0.11f + 0.085f
        return 1f - smoothstep(radius * 0.85f, radius * 1.55f, dist)
    }

    /** ボディ全体の drawBitmapMesh 頂点配列を更新する */
    fun fillBodyMesh(
        pose: BocchiPose,
        rig: CharacterRig,
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
                val (du, dv) = deformUv(u, v, pose, rig)
                outVerts[idx++] = left + du * width
                outVerts[idx++] = top + dv * height
            }
        }
    }

    /** 顔パッチの drawBitmapMesh 頂点配列を、ボディと同一の変形関数で更新する */
    fun fillFaceMesh(
        pose: BocchiPose,
        rig: CharacterRig,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray
    ) {
        var idx = 0
        val box = rig.faceBox
        for (row in 0..FACE_ROWS) {
            val v = box.v0 + (row.toFloat() / FACE_ROWS) * box.height
            for (col in 0..FACE_COLS) {
                val u = box.u0 + (col.toFloat() / FACE_COLS) * box.width
                val (du, dv) = deformUv(u, v, pose, rig)
                outVerts[idx++] = left + du * width
                outVerts[idx++] = top + dv * height
            }
        }
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
 * キャラクターごとの動きの性格。
 *
 *   ひとり : 小刻みで不器用。パニックしやすい。
 *   喜多   : 大きめの弾みと活発な表情。髪は短いので揺れは控えめ。
 *   虹夏   : 元気いっぱい。ドラムの腕が150BPMで動く。
 *   リョウ : クールで省エネ。揺れは小さく、表情もめったに崩さない。
 */
enum class MotionStyle(
    val bounceGain: Float,
    val yawGain: Float,
    val rollGain: Float,
    val hairGain: Float,
    val strumGain: Float,
    val blinkRange: ClosedFloatingPointRange<Float>,
    val idleExpressions: List<BocchiExpression>,
    val expressRange: ClosedFloatingPointRange<Float>,
    val playsDrums: Boolean = false
) {
    HITORI(
        bounceGain = 1.0f, yawGain = 1.0f, rollGain = 1.0f, hairGain = 1.0f, strumGain = 1.0f,
        blinkRange = 3.1f..4.2f,
        idleExpressions = listOf(BocchiExpression.HAPPY_SMILE, BocchiExpression.SHY_BASE),
        expressRange = 4.5f..7.0f
    ),
    KITA(
        bounceGain = 1.25f, yawGain = 1.05f, rollGain = 0.95f, hairGain = 0.62f, strumGain = 1.1f,
        blinkRange = 2.7f..3.7f,
        idleExpressions = listOf(
            BocchiExpression.LAUGH_JOY,
            BocchiExpression.HAPPY_SMILE,
            BocchiExpression.WINK,
            BocchiExpression.SURPRISED
        ),
        expressRange = 2.6f..4.4f
    ),
    NIJIKA(
        bounceGain = 1.35f, yawGain = 0.85f, rollGain = 0.90f, hairGain = 0.85f, strumGain = 0f,
        blinkRange = 2.5f..3.4f,
        idleExpressions = listOf(
            BocchiExpression.LAUGH_JOY,
            BocchiExpression.HAPPY_SMILE,
            BocchiExpression.WINK
        ),
        expressRange = 2.2f..3.6f,
        playsDrums = true
    ),
    RYO(
        bounceGain = 0.55f, yawGain = 0.55f, rollGain = 0.45f, hairGain = 0.40f, strumGain = 0.85f,
        blinkRange = 3.8f..5.6f,
        idleExpressions = listOf(BocchiExpression.SHY_BASE, BocchiExpression.HAPPY_SMILE),
        expressRange = 5.5f..9.0f
    )
}

/**
 * Live2D 物理演算（減衰バネ振り子チェーン＋自動瞬き＋ビート同期＋表情切替）を
 * 時間ステップで進めるエンジン。
 */
class BocchiPhysicsEngine(private val style: MotionStyle = MotionStyle.HITORI) {

    private var timeSec = 0f
    private var scrollImpulse = 0f
    private var strumEnergy = 0f
    private var panicTimer = 0f
    private var grooveTimer = 0f
    private var targetGazeX = 0f
    private var targetGazeY = 0f
    private var baseExpression = BocchiExpression.SHY_BASE

    // 表情スケジューラ（一定時間ごとに自発的に表情を変える）
    private var idleExpression = BocchiExpression.SHY_BASE
    private var nextExpressionSec = FIRST_EXPRESSION_SEC
    private var expressionUntilSec = 0f
    private var expressionRng = EXPRESSION_SEED

    // 瞬きタイマー（自然な単発・2連瞬きを交互に発生させる）
    private var nextBlinkSec = FIRST_BLINK_SEC
    private var blinkCount = 0

    // ドラムの打撃検出（ビートの頭で1回だけヒットを出す）
    private var lastBeatIndex = -1
    private var lastHit = DrumPad.NONE
    private var hitFlashSec = 0f

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
                headReaction()
                ahogeSpring.addImpulse(2.4f)
                cubeSpring.addImpulse(-2.0f)
            }

            BocchiHitZone.GUITAR_STRUM -> {
                grooveTimer = GROOVE_HOLD_SEC
                strumEnergy = 1f
                hairMidSpring.addImpulse(1.4f)
                instrumentReaction()
            }

            BocchiHitZone.STAGE_BURST -> {
                strumEnergy = max(strumEnergy, 0.65f)
                ahogeSpring.addImpulse(1.1f)
            }
        }
    }

    /** 頭タップの反応。キャラごとに表れ方が違う。 */
    private fun headReaction() {
        when (style) {
            MotionStyle.HITORI -> panicTimer = PANIC_HOLD_SEC
            MotionStyle.KITA -> {
                grooveTimer = GROOVE_HOLD_SEC
                forceExpression(BocchiExpression.WINK, TAP_EXPRESSION_SEC)
            }
            MotionStyle.NIJIKA -> {
                grooveTimer = GROOVE_HOLD_SEC
                forceExpression(BocchiExpression.LAUGH_JOY, TAP_EXPRESSION_SEC)
            }
            MotionStyle.RYO -> {
                grooveTimer = GROOVE_HOLD_SEC * 0.6f
                forceExpression(BocchiExpression.SURPRISED, TAP_EXPRESSION_SEC * 0.8f)
            }
        }
    }

    /** 楽器タップの反応。 */
    private fun instrumentReaction() {
        when (style) {
            MotionStyle.HITORI, MotionStyle.KITA ->
                forceExpression(BocchiExpression.AWAKENED_GROOVE, SOLO_EXPRESSION_SEC)

            MotionStyle.NIJIKA -> {
                lastHit = DrumPad.SNARE
                hitFlashSec = HIT_FLASH_SEC
            }

            MotionStyle.RYO ->
                forceExpression(BocchiExpression.HAPPY_SMILE, SOLO_EXPRESSION_SEC)
        }
    }

    private fun forceExpression(expression: BocchiExpression, holdSec: Float) {
        idleExpression = expression
        expressionUntilSec = timeSec + holdSec
        nextExpressionSec = max(nextExpressionSec, expressionUntilSec + EXPRESSION_COOLDOWN_SEC)
    }

    fun step(dtSec: Float): BocchiPose {
        val dt = dtSec.coerceIn(MIN_DT, MAX_DT)
        timeSec += dt

        // タイマー減衰
        panicTimer = max(0f, panicTimer - dt)
        grooveTimer = max(0f, grooveTimer - dt)
        strumEnergy = max(0f, strumEnergy - dt * STRUM_DECAY)
        hitFlashSec = max(0f, hitFlashSec - dt)
        scrollImpulse *= exp(-dt * SCROLL_DECAY)
        targetGazeX *= exp(-dt * GAZE_DECAY)
        targetGazeY *= exp(-dt * GAZE_DECAY)
        scheduleExpression()

        // 150 BPM ビート周期 (2.5 Hz)
        val beatHz = BocchiLive2dPolicy.STAGE_BPM / 60f
        val beatPhase = timeSec * beatHz * 2f * PI.toFloat()
        val beatBounce = -abs(sin(beatPhase * 0.5f)) * (0.45f + 0.55f * strumEnergy) * style.bounceGain

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

        val angleX = (idleYaw * style.yawGain + targetGazeX * 0.55f - scrollImpulse * 0.65f + panicJitter)
            .coerceIn(-1f, 1f)
        val angleY = (idlePitch + targetGazeY * 0.45f).coerceIn(-1f, 1f)
        val angleZ = (idleRoll * style.rollGain - scrollImpulse * 0.55f + panicJitter * 0.8f).coerceIn(-1f, 1f)
        val bodyAngleX = (0.25f * sin(timeSec * 0.95f) - scrollImpulse * 0.45f).coerceIn(-1f, 1f)
        val bodyAngleZ = (0.22f * sin(timeSec * 0.95f + 0.3f) - scrollImpulse * 0.35f).coerceIn(-1f, 1f)

        // 頭部・体幹の運動を6系統の物理バネ振り子へ入力
        val headDrive = -angleX * 0.85f - angleZ * 0.65f + scrollImpulse * 0.9f
        val hairFront = hairFrontSpring.step(headDrive, dt) * style.hairGain
        val hairMid = hairMidSpring.step(headDrive * 1.1f - bodyAngleX * 0.5f, dt) * style.hairGain
        val hairTip = hairTipSpring.step(hairMid * 1.25f, dt) * style.hairGain
        val ahoge = ahogeSpring.step(headDrive * 1.3f + beatBounce * 0.6f, dt)
        val cubeSwing = cubeSpring.step(headDrive * 1.15f + beatBounce * 0.5f, dt)
        val skirtSway = skirtSpring.step(-bodyAngleX * 1.1f + scrollImpulse * 0.8f, dt)

        // 楽器のネック揺れと右手ストローク
        val guitarRock = (0.28f * sin(beatPhase * 0.5f + 0.6f) +
            strumEnergy * 0.62f * sin(beatPhase) +
            scrollImpulse * 0.35f).coerceIn(-1f, 1f)
        val armStrum = (style.strumGain * (0.22f + 0.78f * strumEnergy) *
            sin(beatPhase * 2f)).coerceIn(-1f, 1f)

        val drums = drumMotion(beatPhase, headDrive)

        val eyeOpen = computeEyeOpen(timeSec)
        val modeExpr = when {
            panicTimer > 0f -> BocchiExpression.BOCCHI_PANIC
            expressionUntilSec > timeSec -> idleExpression
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
            stickArm = drums.stickArm,
            snareArm = drums.snareArm,
            kickPedal = drums.kickPedal,
            kitShake = drums.kitShake,
            drumHit = drums.hit,
            expression = finalExpr
        )
    }

    /** ドラムの腕・ペダルと打撃。8分のハイハット、2・4拍のスネア、1・3拍のキック。 */
    private fun drumMotion(beatPhase: Float, headDrive: Float): DrumMotion {
        if (!style.playsDrums) {
            return DrumMotion()
        }
        val beatHz = BocchiLive2dPolicy.STAGE_BPM / 60f
        val beatIndex = (timeSec * beatHz).toInt()
        val inBeat = (timeSec * beatHz) - beatIndex
        if (beatIndex != lastBeatIndex) {
            lastBeatIndex = beatIndex
            lastHit = when (beatIndex % 4) {
                0 -> DrumPad.KICK
                1 -> DrumPad.HI_HAT
                2 -> DrumPad.SNARE
                else -> DrumPad.CRASH
            }
            hitFlashSec = HIT_FLASH_SEC
        }

        // 打ち下ろしは拍の頭、振り上げは拍の裏。
        val swing = sin(beatPhase - PI.toFloat() * 0.5f)
        val snareSwing = sin(beatPhase * 0.5f + PI.toFloat())
        val pedal = max(0f, -sin(beatPhase * 2f))
        val shake = if (hitFlashSec > 0f) sin(hitFlashSec * 90f) * hitFlashSec / HIT_FLASH_SEC else 0f
        return DrumMotion(
            stickArm = (swing * 0.85f + headDrive * 0.10f).coerceIn(-1f, 1f),
            snareArm = (snareSwing * 0.55f).coerceIn(-1f, 1f),
            kickPedal = (pedal * 0.6f).coerceIn(-1f, 1f),
            kitShake = shake,
            hit = if (hitFlashSec > 0f && inBeat < HIT_VISIBLE_SEC) lastHit else DrumPad.NONE
        )
    }

    private fun scheduleExpression() {
        if (timeSec < nextExpressionSec) {
            return
        }
        if (timeSec < expressionUntilSec) {
            return
        }
        idleExpression = style.idleExpressions[nextExpression() % style.idleExpressions.size]
        val hold = style.expressRange.start +
            nextExpression01() * (style.expressRange.endInclusive - style.expressRange.start)
        expressionUntilSec = timeSec + hold
        nextExpressionSec = expressionUntilSec + EXPRESSION_COOLDOWN_SEC + nextExpression01() * 2f
    }

    private fun nextExpression(): Int {
        expressionRng = expressionRng * 1103515245 + 12345
        return ((expressionRng ushr 16) and 0x7FFF).toInt()
    }

    private fun nextExpression01(): Float = (nextExpression() % 1000) / 1000f

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
            nextBlinkSec = now + style.blinkRange.start +
                nextExpression01() * (style.blinkRange.endInclusive - style.blinkRange.start)
            return 1f
        }
        val local = elapsed % SINGLE_BLINK_DURATION
        val half = SINGLE_BLINK_DURATION * 0.5f
        return (abs(local - half) / half).coerceIn(0f, 1f)
    }

    private class DrumMotion(
        val stickArm: Float = 0f,
        val snareArm: Float = 0f,
        val kickPedal: Float = 0f,
        val kitShake: Float = 0f,
        val hit: DrumPad = DrumPad.NONE
    )

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
        const val FIRST_EXPRESSION_SEC = 2.2f
        const val EXPRESSION_COOLDOWN_SEC = 2.4f
        const val TAP_EXPRESSION_SEC = 1.6f
        const val SOLO_EXPRESSION_SEC = 2.4f
        const val HIT_FLASH_SEC = 0.14f
        const val HIT_VISIBLE_SEC = 0.09f
        const val EXPRESSION_SEED = 7919L
    }
}
