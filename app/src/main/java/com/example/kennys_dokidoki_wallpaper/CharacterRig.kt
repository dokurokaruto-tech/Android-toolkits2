package com.example.kennys_dokidoki_wallpaper

/**
 * キャラクターごとのリグ（変形の基準点）。全素材は592×750で共通なので、
 * 座標は正規化UV(0..1)で持つ。
 *
 *   rig = faceBox + head + torso + hair + [instrument] + [limbs] + eyes
 *
 * 楽器を持つキャラは instrument を持ち、ドラムのキャラは instrument の代わりに
 * limbs（腕・ペダル）を持つ。null の部位はその変形を行わない。
 */
data class RigPoint(val u: Float, val v: Float)

/** ガウス重みの範囲。中心と半径をUVで指定する。 */
data class RigRegion(val center: RigPoint, val radiusU: Float, val radiusV: Float)

/** 顔パッチの切り出し範囲。ボディとパッチで必ず同じ値を使う。 */
data class FaceBox(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    val left: Float get() = u0
    val top: Float get() = v0
    val width: Float get() = u1 - u0
    val height: Float get() = v1 - v0
}

data class HeadRig(
    val pivot: RigPoint,
    val halfWidthU: Float,
    /** これより下は頭の回転に追従しない。 */
    val bottomV: Float,
    /** これより上は頭の回転に追従しない。 */
    val topV: Float
)

data class TorsoRig(
    val pivot: RigPoint,
    /** これより下（足元）は固定し、接地感を保つ。 */
    val anchorV: Float,
    val breath: RigRegion,
    /** 上半身の揺れを許可する領域。null なら全身。 */
    val swayMask: RigRegion? = null
)

data class HairRig(
    val leftBandU: ClosedFloatingPointRange<Float>,
    val rightBandU: ClosedFloatingPointRange<Float>,
    val topV: Float,
    val bottomV: Float
)

data class InstrumentRig(
    /** 弦の中心線 A→B。この線の周りだけ剛体として動かす。 */
    val axisA: RigPoint,
    val axisB: RigPoint,
    val pivot: RigPoint,
    /** 弦を弾く手の位置。 */
    val pluckHand: RigRegion,
    /** 描画する弦エフェクトの両端。 */
    val bridge: RigPoint,
    val nut: RigPoint
)

enum class LimbChannel { STICK_ARM, SNARE_ARM, PEDAL_FOOT }

/** 腕や足を骨の付け根で回す変形。region 内だけが動く。 */
data class LimbRig(
    val pivot: RigPoint,
    val region: RigRegion,
    val channel: LimbChannel
)

data class EyeRig(
    val left: RigPoint,
    val right: RigPoint
)

data class CharacterRig(
    val faceBox: FaceBox,
    val head: HeadRig,
    val torso: TorsoRig,
    val hair: HairRig,
    val eyes: EyeRig,
    val bangs: RigRegion?,
    val ahoge: RigRegion?,
    val accessory: RigRegion?,
    val skirt: RigRegion?,
    val instrument: InstrumentRig?,
    val limbs: List<LimbRig>,
    /** 髪飾りやリボンのキラリ位置。 */
    val glints: List<RigPoint>,
    /** ドラムの打面。空なら打楽器なし。 */
    val drumPads: DrumPads?
)

/** ドラムの打点。ヒットエフェクトと腕の同期に使う。 */
data class DrumPads(
    val snare: RigPoint,
    val hiHat: RigPoint,
    val crash: RigPoint,
    val kick: RigPoint
)

object CharacterRigs {

    /** 後藤ひとり。既存の592×750リグをそのまま数値化したもの。 */
    val HITORI = CharacterRig(
        faceBox = FaceBox(160f / 592f, 90f / 750f, 316f / 592f, 216f / 750f),
        head = HeadRig(RigPoint(0.40f, 0.285f), 0.13f, bottomV = 0.32f, topV = 0.12f),
        torso = TorsoRig(
            pivot = RigPoint(0.40f, 0.76f),
            anchorV = 0.90f,
            breath = RigRegion(RigPoint(0.40f, 0.37f), 0.16f, 0.10f)
        ),
        hair = HairRig(0.18f..0.28f, 0.50f..0.72f, topV = 0.16f, bottomV = 0.65f),
        eyes = EyeRig(RigPoint(0.352f, 0.203f), RigPoint(0.453f, 0.205f)),
        bangs = RigRegion(RigPoint(0.40f, 0.15f), 0.09f, 0.05f),
        ahoge = RigRegion(RigPoint(0.46f, 0.03f), 0.06f, 0.04f),
        accessory = RigRegion(RigPoint(0.21f, 0.16f), 0.08f, 0.06f),
        skirt = RigRegion(RigPoint(0.42f, 0.74f), 0.18f, 0.06f),
        instrument = InstrumentRig(
            axisA = RigPoint(0.26f, 0.65f),
            axisB = RigPoint(0.96f, 0.35f),
            pivot = RigPoint(0.38f, 0.58f),
            pluckHand = RigRegion(RigPoint(0.335f, 0.615f), 0.055f, 0.048f),
            bridge = RigPoint(0.310f, 0.625f),
            nut = RigPoint(0.855f, 0.395f)
        ),
        limbs = emptyList(),
        glints = listOf(RigPoint(0.250f, 0.125f), RigPoint(0.248f, 0.165f)),
        drumPads = null
    )

    /** 喜多郁代。ポニーテールと星のピン、レスポールJr. */
    val KITA = CharacterRig(
        faceBox = FaceBox(160f / 592f, 8f / 750f, 452f / 592f, 268f / 750f),
        head = HeadRig(RigPoint(0.380f, 0.190f), 0.155f, bottomV = 0.245f, topV = 0.020f),
        torso = TorsoRig(
            pivot = RigPoint(0.420f, 0.440f),
            anchorV = 0.90f,
            breath = RigRegion(RigPoint(0.420f, 0.300f), 0.130f, 0.070f)
        ),
        hair = HairRig(0.10f..0.30f, 0.42f..0.60f, topV = 0.10f, bottomV = 0.48f),
        eyes = EyeRig(RigPoint(0.346f, 0.140f), RigPoint(0.414f, 0.136f)),
        bangs = RigRegion(RigPoint(0.370f, 0.115f), 0.100f, 0.045f),
        ahoge = null,
        accessory = RigRegion(RigPoint(0.408f, 0.100f), 0.045f, 0.035f),
        skirt = RigRegion(RigPoint(0.440f, 0.455f), 0.130f, 0.050f),
        instrument = InstrumentRig(
            axisA = RigPoint(0.320f, 0.500f),
            axisB = RigPoint(0.660f, 0.160f),
            pivot = RigPoint(0.360f, 0.420f),
            pluckHand = RigRegion(RigPoint(0.330f, 0.470f), 0.050f, 0.045f),
            bridge = RigPoint(0.336f, 0.476f),
            nut = RigPoint(0.655f, 0.160f)
        ),
        limbs = emptyList(),
        glints = listOf(RigPoint(0.408f, 0.098f), RigPoint(0.424f, 0.112f)),
        drumPads = null
    )

    /** 伊地知虹夏。椅子に座ってドラムを叩く。下半身とドラムは固定。 */
    val NIJIKA = CharacterRig(
        faceBox = FaceBox(0.33f, 0.08f, 0.63f, 0.30f),
        head = HeadRig(RigPoint(0.473f, 0.230f), 0.110f, bottomV = 0.275f, topV = 0.085f),
        torso = TorsoRig(
            pivot = RigPoint(0.490f, 0.480f),
            anchorV = 0.58f,
            breath = RigRegion(RigPoint(0.490f, 0.330f), 0.090f, 0.060f),
            swayMask = RigRegion(RigPoint(0.500f, 0.380f), 0.130f, 0.200f)
        ),
        hair = HairRig(0.33f..0.42f, 0.55f..0.72f, topV = 0.10f, bottomV = 0.42f),
        eyes = EyeRig(RigPoint(0.427f, 0.207f), RigPoint(0.507f, 0.200f)),
        bangs = RigRegion(RigPoint(0.470f, 0.130f), 0.095f, 0.040f),
        ahoge = RigRegion(RigPoint(0.436f, 0.083f), 0.030f, 0.030f),
        accessory = RigRegion(RigPoint(0.575f, 0.150f), 0.060f, 0.055f),
        skirt = null,
        instrument = null,
        limbs = listOf(
            LimbRig(
                pivot = RigPoint(0.507f, 0.293f),
                region = RigRegion(RigPoint(0.430f, 0.140f), 0.085f, 0.150f),
                channel = LimbChannel.STICK_ARM
            ),
            LimbRig(
                pivot = RigPoint(0.557f, 0.313f),
                region = RigRegion(RigPoint(0.563f, 0.440f), 0.075f, 0.130f),
                channel = LimbChannel.SNARE_ARM
            ),
            LimbRig(
                pivot = RigPoint(0.500f, 0.520f),
                region = RigRegion(RigPoint(0.565f, 0.760f), 0.095f, 0.150f),
                channel = LimbChannel.PEDAL_FOOT
            )
        ),
        glints = listOf(RigPoint(0.575f, 0.148f), RigPoint(0.600f, 0.165f)),
        drumPads = DrumPads(
            snare = RigPoint(0.498f, 0.567f),
            hiHat = RigPoint(0.363f, 0.327f),
            crash = RigPoint(0.726f, 0.460f),
            kick = RigPoint(0.186f, 0.740f)
        )
    )

    /** 山田リョウ。クールなベーシスト。髪は短く、揺れは控えめ。 */
    val RYO = CharacterRig(
        faceBox = FaceBox(0.33f, 0.02f, 0.57f, 0.185f),
        head = HeadRig(RigPoint(0.443f, 0.140f), 0.095f, bottomV = 0.175f, topV = 0.020f),
        torso = TorsoRig(
            pivot = RigPoint(0.450f, 0.360f),
            anchorV = 0.92f,
            breath = RigRegion(RigPoint(0.450f, 0.220f), 0.100f, 0.070f)
        ),
        hair = HairRig(0.33f..0.42f, 0.50f..0.60f, topV = 0.02f, bottomV = 0.21f),
        eyes = EyeRig(RigPoint(0.405f, 0.104f), RigPoint(0.478f, 0.100f)),
        bangs = RigRegion(RigPoint(0.450f, 0.060f), 0.090f, 0.035f),
        ahoge = null,
        accessory = RigRegion(RigPoint(0.472f, 0.062f), 0.035f, 0.030f),
        skirt = null,
        instrument = InstrumentRig(
            axisA = RigPoint(0.330f, 0.420f),
            axisB = RigPoint(0.840f, 0.200f),
            pivot = RigPoint(0.400f, 0.380f),
            pluckHand = RigRegion(RigPoint(0.450f, 0.410f), 0.050f, 0.040f),
            bridge = RigPoint(0.400f, 0.424f),
            nut = RigPoint(0.830f, 0.200f)
        ),
        limbs = emptyList(),
        glints = listOf(RigPoint(0.472f, 0.060f)),
        drumPads = null
    )

    fun of(character: HomeCharacter): CharacterRig = when (character) {
        HomeCharacter.HITORI -> HITORI
        HomeCharacter.KITA -> KITA
        HomeCharacter.NIJIKA -> NIJIKA
        HomeCharacter.RYO -> RYO
    }
}
