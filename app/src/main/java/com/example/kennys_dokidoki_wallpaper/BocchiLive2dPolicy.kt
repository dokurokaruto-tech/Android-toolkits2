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
    BOCCHI_PANIC("live2d/bocchi/bocchi_face_panic.webp"),

    // ひとりは既存差分へ寄せ、他の3人は専用差分を持つ
    WINK_JOY("live2d/bocchi/bocchi_face_smile.webp"),
    SURPRISED("live2d/bocchi/bocchi_face_panic.webp"),
    DREAMY("live2d/bocchi/bocchi_face_awakened.webp")
}

enum class BocchiHitZone {
    HEAD_PANIC,
    HEAD_EMOTE,
    GUITAR_STRUM,
    STAGE_BURST
}

enum class InstrumentKind {
    GUITAR,
    DRUMS
}

data class UvPoint(val u: Float, val v: Float)

data class UvBox(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

/** 592x750スプライト上の計測点。キャラごとの顔・楽器・髪の位置を表す。 */
data class CharacterRig(
    val face: UvBox,
    val eyeLeft: UvPoint,
    val eyeRight: UvPoint,
    val headPivot: UvPoint,
    val bodyPivot: UvPoint,
    val ahoge: UvPoint,
    val tuftCenter: UvPoint,
    val ornamentA: UvPoint,
    val ornamentB: UvPoint,
    val instrument: InstrumentKind,
    val weightA: UvPoint,
    val weightB: UvPoint,
    val instrumentPivot: UvPoint,
    val stringBridge: UvPoint,
    val stringNut: UvPoint,
    val strumHand: UvPoint,
    val swayCenter: UvPoint,
    val breathCenter: UvPoint,
    val hairLeft: UvBox,
    val hairRight: UvBox,
    val drumLeft: UvPoint,
    val drumRight: UvPoint,
    val cymbalLeft: UvPoint,
    val cymbalRight: UvPoint,
    val stickLeft: UvPoint,
    val stickRight: UvPoint
)

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
    val drumLeft: Float = 0f,
    val drumRight: Float = 0f,
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

    private const val TEX_W = 592f
    private const val TEX_H = 750f

    private fun pt(x: Float, y: Float) = UvPoint(x / TEX_W, y / TEX_H)

    private fun bx(x0: Float, y0: Float, x1: Float, y1: Float) =
        UvBox(x0 / TEX_W, y0 / TEX_H, x1 / TEX_W, y1 / TEX_H)

    // 既存ぼっち素材の計測点。従来定数（FACE_U0等）と同一の顔範囲を使う
    val BOCCHI_RIG = CharacterRig(
        face = UvBox(FACE_U0, FACE_V0, FACE_U1, FACE_V1),
        eyeLeft = UvPoint(EYE_LEFT_U, EYE_LEFT_V),
        eyeRight = UvPoint(EYE_RIGHT_U, EYE_RIGHT_V),
        headPivot = pt(237f, 214f),
        bodyPivot = pt(237f, 570f),
        ahoge = pt(272f, 22f),
        tuftCenter = pt(124f, 120f),
        ornamentA = UvPoint(CUBE_BLUE_U, CUBE_BLUE_V),
        ornamentB = UvPoint(CUBE_YELLOW_U, CUBE_YELLOW_V),
        instrument = InstrumentKind.GUITAR,
        weightA = UvPoint(0.26f, 0.65f),
        weightB = UvPoint(0.96f, 0.35f),
        instrumentPivot = pt(225f, 435f),
        stringBridge = UvPoint(GUITAR_BRIDGE_U, GUITAR_BRIDGE_V),
        stringNut = UvPoint(GUITAR_NUT_U, GUITAR_NUT_V),
        strumHand = pt(198f, 461f),
        swayCenter = pt(249f, 555f),
        breathCenter = pt(237f, 278f),
        hairLeft = bx(-40f, 120f, 166f, 472f),
        hairRight = bx(296f, 120f, 426f, 488f),
        drumLeft = pt(198f, 461f),
        drumRight = pt(198f, 461f),
        cymbalLeft = pt(198f, 461f),
        cymbalRight = pt(198f, 461f),
        stickLeft = pt(198f, 461f),
        stickRight = pt(198f, 461f)
    )

    val KITA_RIG = CharacterRig(
        face = bx(162f, 92f, 312f, 222f),
        eyeLeft = pt(203f, 149f),
        eyeRight = pt(273f, 149f),
        headPivot = pt(238f, 228f),
        bodyPivot = pt(238f, 570f),
        ahoge = pt(235f, 20f),
        tuftCenter = pt(335f, 110f),
        ornamentA = pt(238f, 235f),
        ornamentB = pt(335f, 105f),
        instrument = InstrumentKind.GUITAR,
        weightA = pt(165f, 505f),
        weightB = pt(550f, 290f),
        instrumentPivot = pt(225f, 435f),
        stringBridge = pt(185f, 495f),
        stringNut = pt(515f, 320f),
        strumHand = pt(175f, 492f),
        swayCenter = pt(240f, 585f),
        breathCenter = pt(235f, 315f),
        hairLeft = bx(80f, 80f, 175f, 300f),
        hairRight = bx(280f, 80f, 360f, 300f),
        drumLeft = pt(175f, 492f),
        drumRight = pt(175f, 492f),
        cymbalLeft = pt(175f, 492f),
        cymbalRight = pt(175f, 492f),
        stickLeft = pt(175f, 492f),
        stickRight = pt(175f, 492f)
    )

    val NIJIKA_RIG = CharacterRig(
        face = bx(186f, 122f, 332f, 250f),
        eyeLeft = pt(226f, 178f),
        eyeRight = pt(295f, 177f),
        headPivot = pt(260f, 258f),
        bodyPivot = pt(260f, 570f),
        ahoge = pt(205f, 32f),
        tuftCenter = pt(330f, 95f),
        ornamentA = pt(330f, 95f),
        ornamentB = pt(205f, 32f),
        instrument = InstrumentKind.DRUMS,
        weightA = pt(140f, 585f),
        weightB = pt(330f, 560f),
        instrumentPivot = pt(245f, 640f),
        stringBridge = pt(140f, 585f),
        stringNut = pt(330f, 560f),
        strumHand = pt(395f, 375f),
        swayCenter = pt(250f, 470f),
        breathCenter = pt(260f, 335f),
        hairLeft = bx(150f, 120f, 205f, 430f),
        hairRight = bx(335f, 60f, 505f, 460f),
        drumLeft = pt(140f, 585f),
        drumRight = pt(330f, 560f),
        cymbalLeft = pt(85f, 475f),
        cymbalRight = pt(475f, 480f),
        stickLeft = pt(105f, 170f),
        stickRight = pt(412f, 300f)
    )

    val RYO_RIG = CharacterRig(
        face = bx(162f, 92f, 312f, 215f),
        eyeLeft = pt(204f, 147f),
        eyeRight = pt(272f, 147f),
        headPivot = pt(238f, 218f),
        bodyPivot = pt(238f, 570f),
        ahoge = pt(238f, 30f),
        tuftCenter = pt(185f, 240f),
        ornamentA = pt(215f, 292f),
        ornamentB = pt(185f, 240f),
        instrument = InstrumentKind.GUITAR,
        weightA = pt(130f, 500f),
        weightB = pt(560f, 275f),
        instrumentPivot = pt(220f, 440f),
        stringBridge = pt(115f, 505f),
        stringNut = pt(500f, 295f),
        strumHand = pt(180f, 480f),
        swayCenter = pt(230f, 600f),
        breathCenter = pt(235f, 320f),
        hairLeft = bx(95f, 60f, 185f, 215f),
        hairRight = bx(275f, 60f, 335f, 215f),
        drumLeft = pt(180f, 480f),
        drumRight = pt(180f, 480f),
        cymbalLeft = pt(180f, 480f),
        cymbalRight = pt(180f, 480f),
        stickLeft = pt(180f, 480f),
        stickRight = pt(180f, 480f)
    )

    private const val TEX_ASPECT = 592f / 750f
    private const val INV_ASPECT = 750f / 592f
    private const val HALF_BLINK_THRESHOLD = 0.68f
    private const val CLOSED_BLINK_THRESHOLD = 0.25f

    /** タップ座標をキャラクターの正規化UVへ変換し、反応部位を判定する */
    fun hitZone(u: Float, v: Float): BocchiHitZone = hitZone(u, v, BOCCHI_RIG)

    fun hitZone(u: Float, v: Float, rig: CharacterRig): BocchiHitZone {
        val face = rig.face
        if (u in (face.u0 - 0.06f)..(face.u1 + 0.06f) && v in (face.v0 - 0.10f)..(face.v1 + 0.06f)) {
            return BocchiHitZone.HEAD_PANIC
        }
        val onInstrument = when (rig.instrument) {
            InstrumentKind.GUITAR -> u in 0.12f..0.92f && v in 0.32f..0.76f
            InstrumentKind.DRUMS -> u in 0.05f..0.95f && v in 0.55f..1.0f
        }
        if (onInstrument) {
            return BocchiHitZone.GUITAR_STRUM
        }
        return BocchiHitZone.STAGE_BURST
    }

    /** 機能セクションに応じたベース表情を返す */
    fun sectionExpression(section: HomeSection): BocchiExpression = when (section) {
        HomeSection.BUILDER -> BocchiExpression.AWAKENED_GROOVE
        HomeSection.TAGS -> BocchiExpression.WINK_JOY
        HomeSection.DIARY -> BocchiExpression.DREAMY
        HomeSection.IMAGE_SETS -> BocchiExpression.HAPPY_SMILE
        HomeSection.ALL_IMAGES -> BocchiExpression.SHY_BASE
        HomeSection.SETTINGS -> BocchiExpression.SURPRISED
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
    fun deformUv(u: Float, v: Float, pose: BocchiPose): Pair<Float, Float> =
        deformUv(u, v, pose, BOCCHI_RIG)

    fun deformUv(u: Float, v: Float, pose: BocchiPose, rig: CharacterRig): Pair<Float, Float> {
        var du = 0f
        var dv = 0f

        val guitarW = if (rig.instrument == InstrumentKind.GUITAR) {
            guitarWeight(u, v, rig)
        } else {
            0f
        }

        // 1. 上半身の揺れと150BPMビートバウンス
        val upperW = 1f - smoothstep(0.74f, 0.90f, v)
        if (upperW > 0f) {
            val relX = (u - rig.bodyPivot.u) * TEX_ASPECT
            val relY = v - rig.bodyPivot.v
            val bodyRad = pose.bodyAngleZ * 0.042f * upperW
            val cosB = cos(bodyRad)
            val sinB = sin(bodyRad)
            du += ((relX * cosB - relY * sinB - relX) * INV_ASPECT) + pose.bodyAngleX * 0.018f * upperW
            dv += (relX * sinB + relY * cosB - relY) + pose.beatBounce * 0.010f * upperW
        }

        // 2. 胸・肩の呼吸デフォーマ（ギター領域は除外して歪みを防ぐ）
        val breathW = gaussian2d(
            u, v, rig.breathCenter.u, rig.breathCenter.v, 0.16f, 0.10f
        ) * (1f - guitarW)
        du += (u - rig.breathCenter.u) * 0.024f * pose.breath * breathW
        dv -= 0.009f * pose.breath * breathW

        // 3. 頭部 2.5D 回転＋パース移動デフォーマ
        val face = rig.face
        val headW = (1f - smoothstep(rig.headPivot.v - 0.03f, rig.headPivot.v + 0.04f, v)) *
            smoothstep(face.u0 - 0.15f, face.u0 - 0.03f, u) *
            (1f - smoothstep(face.u1 + 0.01f, face.u1 + 0.12f, u))
        if (headW > 0f) {
            val rx = (u - rig.headPivot.u) * TEX_ASPECT
            val ry = v - rig.headPivot.v
            val headRad = pose.angleZ * 0.105f * headW
            val cosH = cos(headRad)
            val sinH = sin(headRad)
            du += (rx * cosH - ry * sinH - rx) * INV_ASPECT
            dv += (rx * sinH + ry * cosH - ry)

            val faceCenterW = gaussian2d(
                u, v, (face.u0 + face.u1) * 0.5f, (face.v0 + face.v1) * 0.5f, 0.13f, 0.09f
            )
            du += pose.angleX * (0.015f * headW + 0.011f * faceCenterW)
            dv += pose.angleY * (0.011f * headW + 0.007f * faceCenterW)
        }

        // 4. アホ毛（ぼっち／虹夏のドリト）のスプリング揺れ
        val ahogeW = gaussian2d(u, v, rig.ahoge.u, rig.ahoge.v, 0.06f, 0.045f) *
            (1f - smoothstep(rig.ahoge.v + 0.01f, rig.ahoge.v + 0.05f, v))
        du += pose.ahoge * 0.028f * ahogeW
        dv += abs(pose.ahoge) * 0.008f * ahogeW

        // 5. 髪飾り・サイド結びなど頭部まわりの小物の揺れ
        val tuftW = gaussian2d(u, v, rig.tuftCenter.u, rig.tuftCenter.v, 0.08f, 0.06f)
        du += pose.cubeSwing * 0.018f * tuftW
        dv += pose.cubeSwing * 0.020f * tuftW

        // 6. 前髪の揺れ
        val bangsW = gaussian2d(u, v, (face.u0 + face.u1) * 0.5f, face.v0 + 0.03f, 0.09f, 0.05f)
        du += pose.hairFront * 0.014f * bangsW

        // 7. ギター／ベースの剛体回転デフォーマ
        if (guitarW > 0f) {
            val gx = (u - rig.instrumentPivot.u) * TEX_ASPECT
            val gy = v - rig.instrumentPivot.v
            val gRad = pose.guitarRock * 0.048f * guitarW
            val cosG = cos(gRad)
            val sinG = sin(gRad)
            du += (gx * cosG - gy * sinG - gx) * INV_ASPECT
            dv += (gx * sinG + gy * cosG - gy)
        }

        // 8. 左右ヘア毛束の2段振り子ウェーブ（ギター背面のみ）
        val hairDu = hairWave(u, v, pose, rig.hairLeft) + hairWave(u, v, pose, rig.hairRight)
        du += hairDu * (1f - guitarW)

        if (rig.instrument == InstrumentKind.GUITAR) {
            // 9. 右手ストローク／指弾きの往復デフォーマ
            val strumW = gaussian2d(u, v, rig.strumHand.u, rig.strumHand.v, 0.055f, 0.048f)
            du += pose.armStrum * 0.010f * strumW
            dv += pose.armStrum * 0.018f * strumW
        } else {
            // 9'. ドラム: 左右スティックの打ち下ろし、打面とシンバルの振動
            val stickLw = gaussian2d(u, v, rig.stickLeft.u, rig.stickLeft.v, 0.075f, 0.075f)
            val stickRw = gaussian2d(u, v, rig.stickRight.u, rig.stickRight.v, 0.075f, 0.075f)
            dv += pose.drumLeft * 0.035f * stickLw + pose.drumRight * 0.035f * stickRw
            du += pose.drumLeft * 0.008f * stickLw - pose.drumRight * 0.008f * stickRw

            val skinLw = gaussian2d(u, v, rig.drumLeft.u, rig.drumLeft.v, 0.10f, 0.035f)
            val skinRw = gaussian2d(u, v, rig.drumRight.u, rig.drumRight.v, 0.10f, 0.035f)
            dv += pose.drumLeft * 0.008f * skinLw + pose.drumRight * 0.008f * skinRw

            val cymLw = gaussian2d(u, v, rig.cymbalLeft.u, rig.cymbalLeft.v, 0.11f, 0.030f)
            val cymRw = gaussian2d(u, v, rig.cymbalRight.u, rig.cymbalRight.v, 0.11f, 0.030f)
            dv += pose.armStrum * 0.006f * (cymLw + cymRw)
        }

        // 10. スカート／パンツ裾の揺れ
        val swayW = gaussian2d(
            u, v, rig.swayCenter.u, rig.swayCenter.v, 0.18f, 0.06f
        ) * (1f - guitarW)
        du += pose.skirtSway * 0.020f * swayW *
            smoothstep(rig.swayCenter.v - 0.08f, rig.swayCenter.v + 0.06f, v)

        return (u + du) to (v + dv)
    }

    private fun hairWave(u: Float, v: Float, pose: BocchiPose, box: UvBox): Float {
        val fadeU = min(0.08f, (box.u1 - box.u0) * 0.3f)
        val fadeV = min(0.09f, (box.v1 - box.v0) * 0.25f)
        val w = smoothstep(box.u0, box.u0 + fadeU, u) *
            (1f - smoothstep(box.u1 - fadeU, box.u1, u)) *
            smoothstep(box.v0, box.v0 + fadeV, v) *
            (1f - smoothstep(box.v1 - fadeV, box.v1, v))
        if (w <= 0f) {
            return 0f
        }
        val depth = ((v - box.v0) / (box.v1 - box.v0)).coerceIn(0f, 1f)
        val wave = pose.hairSideMid * depth + pose.hairSideTip * depth * depth
        return wave * 0.028f * w
    }

    /** ボディ全体の drawBitmapMesh 頂点配列を更新する */
    fun fillBodyMesh(
        pose: BocchiPose,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray,
        rig: CharacterRig = BOCCHI_RIG
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
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        outVerts: FloatArray,
        rig: CharacterRig = BOCCHI_RIG
    ) {
        var idx = 0
        val spanU = rig.face.u1 - rig.face.u0
        val spanV = rig.face.v1 - rig.face.v0
        for (row in 0..FACE_ROWS) {
            val v = rig.face.v0 + (row.toFloat() / FACE_ROWS) * spanV
            for (col in 0..FACE_COLS) {
                val u = rig.face.u0 + (col.toFloat() / FACE_COLS) * spanU
                val (du, dv) = deformUv(u, v, pose, rig)
                outVerts[idx++] = left + du * width
                outVerts[idx++] = top + dv * height
            }
        }
    }

    private fun guitarWeight(u: Float, v: Float, rig: CharacterRig): Float {
        val ax = rig.weightA.u
        val ay = rig.weightA.v
        val bx = rig.weightB.u
        val by = rig.weightB.v
        val gDx = (bx - ax) * TEX_ASPECT
        val gDy = by - ay
        val lenSq = gDx * gDx + gDy * gDy
        val t = (((u - ax) * TEX_ASPECT * gDx + (v - ay) * gDy) / lenSq).coerceIn(0f, 1f)
        val projU = ax + t * (bx - ax)
        val projV = ay + t * (by - ay)
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
    private var emoteTimer = 0f
    private var emoteExpr = BocchiExpression.WINK_JOY
    private var emoteIndex = 0
    private var nextEmoteSec = FIRST_EMOTE_SEC
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

            BocchiHitZone.HEAD_EMOTE -> {
                triggerEmote()
                ahogeSpring.addImpulse(1.6f)
                cubeSpring.addImpulse(1.4f)
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
        emoteTimer = max(0f, emoteTimer - dt)

        // 待機中の自発エモート（ウィンク→うっとり→笑顔→驚きを巡回）
        if (timeSec >= nextEmoteSec && panicTimer <= 0f && grooveTimer <= 0f) {
            triggerEmote()
        }
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

        // 150BPMの8分音符で左右交互に叩くドラムヒット包絡線（8打目に両手アクセント）
        val eighth = timeSec * beatHz * 2f
        val hitIndex = eighth.toInt()
        val hitEnv = exp(-(eighth - hitIndex) * 5.5f) * (0.5f + 0.5f * strumEnergy)
        val drumLeft = if (hitIndex % 8 == 0 || hitIndex % 2 == 0) hitEnv else 0f
        val drumRight = if (hitIndex % 8 == 0 || hitIndex % 2 == 1) hitEnv else 0f

        val eyeOpen = computeEyeOpen(timeSec)
        val modeExpr = when {
            panicTimer > 0f -> BocchiExpression.BOCCHI_PANIC
            grooveTimer > 0f -> BocchiExpression.AWAKENED_GROOVE
            emoteTimer > 0f -> emoteExpr
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
            drumLeft = drumLeft,
            drumRight = drumRight,
            skirtSway = skirtSway,
            glitchIntensity = glitch,
            laserTilt = (-scrollImpulse * 0.75f).coerceIn(-1f, 1f),
            expression = finalExpr
        )
    }

    private fun triggerEmote() {
        emoteExpr = EMOTE_CYCLE[emoteIndex % EMOTE_CYCLE.size]
        emoteIndex++
        emoteTimer = EMOTE_HOLD_SEC
        nextEmoteSec = timeSec + EMOTE_HOLD_SEC + EMOTE_INTERVAL_SEC
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
        const val EMOTE_HOLD_SEC = 2.4f
        const val EMOTE_INTERVAL_SEC = 8.5f
        const val FIRST_EMOTE_SEC = 5.0f
        val EMOTE_CYCLE = listOf(
            BocchiExpression.WINK_JOY,
            BocchiExpression.DREAMY,
            BocchiExpression.HAPPY_SMILE,
            BocchiExpression.SURPRISED
        )
        const val FIRST_BLINK_SEC = 1.6f
        const val SINGLE_BLINK_DURATION = 0.22f
        const val DOUBLE_BLINK_DURATION = 0.44f
        const val MAX_VEL = 12f
    }
}
