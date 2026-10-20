package com.example.kennys_dokidoki_wallpaper

import kotlin.math.max

/** 楽器種別。ステージのエフェクトと変形チャンネルの振り分けに使う。 */
enum class StageInstrument {
    GUITAR,
    BASS,
    DRUMS
}

/** 瞳ハイライトをどちらの目に出すか（ウィンク・閉眼の表情で消すため）。 */
enum class EyeSparkle {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY,
    NONE
}

/**
 * 正規化UVアンカー（実素材から実測）。
 * instA/B はギター・ベースでは弦の両端、ドラムではスネア／クラッシュの打点。
 * decoA/B は髪飾り等の反射位置。使わない場合は 0。
 */
data class RigAnchors(
    val eyeLU: Float,
    val eyeLV: Float,
    val eyeRU: Float,
    val eyeRV: Float,
    val instAU: Float,
    val instAV: Float,
    val instBU: Float,
    val instBV: Float,
    val decoAU: Float,
    val decoAV: Float,
    val decoBU: Float,
    val decoBV: Float
)

/** 素材は共通の592×750リグへ位置合わせ済み。Cubismモデルではない。 */
enum class HomeCharacter(
    val labelRes: Int,
    val crewName: String,
    val accent: Int,
    val bodyAsset: String,
    val instrument: StageInstrument,
    val rig: RigAnchors
) {
    HITORI(
        R.string.character_hitori, "HITORI GOTOH", 0xFFFF3E9D.toInt(),
        BocchiLive2dPolicy.BODY_ASSET, StageInstrument.GUITAR,
        RigAnchors(
            0.352f, 0.203f, 0.453f, 0.205f,
            0.310f, 0.625f, 0.855f, 0.395f,
            0.250f, 0.125f, 0.248f, 0.165f
        )
    ),
    KITA(
        R.string.character_kita, "IKUYO KITA", 0xFFFF635C.toInt(),
        "live2d/kita/kita_body_base.webp", StageInstrument.GUITAR,
        RigAnchors(
            0.355f, 0.215f, 0.465f, 0.215f,
            0.355f, 0.613f, 0.828f, 0.407f,
            0.541f, 0.153f, 0.557f, 0.660f
        )
    ),
    NIJIKA(
        R.string.character_nijika, "NIJIKA IJICHI", 0xFFFFB62E.toInt(),
        "live2d/nijika/nijika_body_base.webp", StageInstrument.DRUMS,
        RigAnchors(
            0.375f, 0.215f, 0.486f, 0.215f,
            0.296f, 0.547f, 0.760f, 0.400f,
            0.101f, 0.407f, 0.296f, 0.780f
        )
    ),
    RYO(
        R.string.character_ryo, "RYO YAMADA", 0xFF448AFF.toInt(),
        "live2d/ryo/ryo_body_base.webp", StageInstrument.BASS,
        RigAnchors(
            0.368f, 0.209f, 0.466f, 0.204f,
            0.257f, 0.641f, 0.810f, 0.343f,
            0.870f, 0.320f, 0f, 0f
        )
    );

    fun faceAsset(expression: BocchiExpression): String? {
        // 後藤ひとりのパニック顔だけ、既存素材名 (panic) が共通 suffix (cheer) と違う
        val suffix = when {
            expression.suffix == null -> return null
            this == HITORI && expression == BocchiExpression.BOCCHI_PANIC -> "panic"
            else -> expression.suffix
        }
        val dir = bodyAsset.substringBeforeLast('/')
        val prefix = bodyAsset.substringAfterLast('/').substringBefore("_body_base")
        return "$dir/${prefix}_face_$suffix.webp"
    }

    fun tapZone(zone: BocchiHitZone): BocchiHitZone {
        // 後藤以外は頭タップでパニックにせず、喜び反応にする
        if (this != HITORI && zone == BocchiHitZone.HEAD_PANIC) {
            return BocchiHitZone.HEAD_REACT
        }
        return zone
    }

    fun adaptPose(pose: BocchiPose): BocchiPose {
        return when (this) {
            HITORI -> pose

            // 喜多: 短髪向けに揺れ抑制、ビートとギターは陽キャらしく強め
            KITA -> pose.copy(
                ahoge = 0f,
                cubeSwing = 0f,
                glitchIntensity = 0f,
                hairSideMid = pose.hairSideMid * 0.65f,
                hairSideTip = pose.hairSideTip * 0.55f,
                beatBounce = pose.beatBounce * 1.15f,
                guitarRock = (pose.guitarRock * 1.1f).coerceIn(-1f, 1f)
            )

            // 虹夏: ストロークを左右スティックの交互打ちへ変換し、ポニーテールを揺らす
            NIJIKA -> pose.copy(
                cubeSwing = 0f,
                glitchIntensity = 0f,
                guitarRock = 0f,
                drumHitL = max(0f, pose.armStrum),
                drumHitR = max(0f, -pose.armStrum),
                armStrum = 0f,
                ponySwing = (pose.hairSideTip * 1.3f).coerceIn(-1f, 1f),
                hairSideMid = pose.hairSideMid * 0.8f,
                skirtSway = pose.skirtSway * 0.3f,
                beatBounce = pose.beatBounce * 1.1f
            )

            // リョウ: 全体に脱力。短いボブと低い重心のベース構えらしく抑える
            RYO -> pose.copy(
                ahoge = 0f,
                cubeSwing = 0f,
                glitchIntensity = 0f,
                hairSideMid = pose.hairSideMid * 0.35f,
                hairSideTip = pose.hairSideTip * 0.3f,
                beatBounce = pose.beatBounce * 0.55f,
                guitarRock = pose.guitarRock * 0.7f,
                armStrum = pose.armStrum * 0.8f,
                angleZ = pose.angleZ * 0.8f,
                skirtSway = pose.skirtSway * 0.5f
            )
        }
    }

    fun sparkle(expression: BocchiExpression): EyeSparkle {
        return when (expression) {
            BocchiExpression.BOCCHI_PANIC -> EyeSparkle.NONE

            BocchiExpression.AWAKENED_GROOVE -> {
                if (this == KITA) {
                    EyeSparkle.LEFT_ONLY
                } else {
                    EyeSparkle.BOTH
                }
            }

            BocchiExpression.IDLE_CHARM -> when (this) {
                KITA -> EyeSparkle.RIGHT_ONLY
                NIJIKA, RYO -> EyeSparkle.NONE
                HITORI -> EyeSparkle.BOTH
            }

            BocchiExpression.HAPPY_SMILE -> {
                if (this == NIJIKA || this == RYO) {
                    EyeSparkle.NONE
                } else {
                    EyeSparkle.BOTH
                }
            }

            BocchiExpression.SINGING_OPEN -> {
                if (this == RYO) {
                    EyeSparkle.NONE
                } else {
                    EyeSparkle.BOTH
                }
            }

            else -> EyeSparkle.BOTH
        }
    }

    fun hudLabel(expression: BocchiExpression): String {
        return when (expression) {
            BocchiExpression.BOCCHI_PANIC -> when (this) {
                HITORI -> "PANIC GLITCH!"
                KITA -> "KITA~N SMILE!"
                NIJIKA -> "ANGEL CHEER!"
                RYO -> "DOYA..."
            }

            BocchiExpression.AWAKENED_GROOVE -> when (this) {
                HITORI -> "GUITAR HERO"
                KITA -> "KITA~N!"
                NIJIKA -> "DRUM GROOVE"
                RYO -> "BASS GROOVE"
            }

            BocchiExpression.SINGING_OPEN -> when (this) {
                HITORI -> "HERO HUM"
                KITA -> "VOCAL ON!"
                NIJIKA -> "COUNT IN!"
                RYO -> "HUMMING..."
            }

            BocchiExpression.SURPRISED -> "EH!?"

            BocchiExpression.IDLE_CHARM -> when (this) {
                HITORI -> "EHEHE..."
                KITA -> "TEHE"
                NIJIKA -> "SHIMOKITA ANGEL"
                RYO -> "SLEEPY..."
            }

            BocchiExpression.HAPPY_SMILE -> "KESSOKU LIVE"
            else -> "2D MESH 150BPM"
        }
    }

    companion object {
        fun fromSaved(name: String?): HomeCharacter = entries.firstOrNull { it.name == name } ?: HITORI
    }
}
