package com.example.kennys_dokidoki_wallpaper

/** 素材は共通の592×750キャンバス。顔・楽器の位置はキャラ別リグが持つ。Cubismモデルではない。 */
enum class HomeCharacter(
    val labelRes: Int,
    val crewName: String,
    val accent: Int,
    val bodyAsset: String,
    val rig: CharacterRig
) {
    HITORI(
        R.string.character_hitori, "HITORI GOTOH", 0xFFFF3E9D.toInt(),
        BocchiLive2dPolicy.BODY_ASSET, BocchiLive2dPolicy.BOCCHI_RIG
    ),
    KITA(
        R.string.character_kita, "IKUYO KITA", 0xFFFF635C.toInt(),
        "live2d/kita/kita_body_base.webp", BocchiLive2dPolicy.KITA_RIG
    ),
    NIJIKA(
        R.string.character_nijika, "NIJIKA IJICHI", 0xFFFFC93E.toInt(),
        "live2d/nijika/nijika_body_base.webp", BocchiLive2dPolicy.NIJIKA_RIG
    ),
    RYO(
        R.string.character_ryo, "RYO YAMADA", 0xFF5B8CFF.toInt(),
        "live2d/ryo/ryo_body_base.webp", BocchiLive2dPolicy.RYO_RIG
    );

    fun faceAsset(expression: BocchiExpression): String? {
        if (expression == BocchiExpression.SHY_BASE) {
            return null
        }
        if (this == HITORI) {
            return expression.assetName
        }
        val suffix = when (expression) {
            BocchiExpression.SHY_BASE -> return null
            BocchiExpression.HALF_BLINK -> "half_blink"
            BocchiExpression.BLINK_CLOSED -> "blink"
            BocchiExpression.HAPPY_SMILE -> "smile"
            BocchiExpression.AWAKENED_GROOVE -> "awakened"
            BocchiExpression.BOCCHI_PANIC -> "cheer"
            BocchiExpression.WINK_JOY -> "wink"
            BocchiExpression.SURPRISED -> "surprised"
            BocchiExpression.DREAMY -> "dreamy"
        }
        val folder = bodyAsset.substringBeforeLast('/')
        return "$folder/${name.lowercase()}_face_$suffix.webp"
    }

    fun tapZone(zone: BocchiHitZone): BocchiHitZone {
        if (this != HITORI && zone == BocchiHitZone.HEAD_PANIC) {
            return BocchiHitZone.HEAD_EMOTE
        }
        return zone
    }

    fun adaptPose(pose: BocchiPose): BocchiPose {
        return when (this) {
            HITORI -> pose
            KITA -> pose.copy(
                ahoge = 0f,
                glitchIntensity = 0f,
                drumLeft = 0f,
                drumRight = 0f,
                cubeSwing = pose.cubeSwing * 0.7f,
                hairSideMid = pose.hairSideMid * 0.65f,
                hairSideTip = pose.hairSideTip * 0.55f
            )

            NIJIKA -> pose.copy(
                glitchIntensity = 0f,
                guitarRock = 0f,
                ahoge = pose.ahoge * 0.8f,
                cubeSwing = pose.cubeSwing * 0.85f,
                hairSideMid = pose.hairSideMid * 0.9f,
                hairSideTip = pose.hairSideTip * 0.9f,
                skirtSway = pose.skirtSway * 0.5f
            )

            RYO -> pose.copy(
                ahoge = 0f,
                cubeSwing = 0f,
                glitchIntensity = 0f,
                drumLeft = 0f,
                drumRight = 0f,
                guitarRock = pose.guitarRock * 0.7f,
                armStrum = pose.armStrum * 0.8f,
                hairSideMid = pose.hairSideMid * 0.35f,
                hairSideTip = pose.hairSideTip * 0.3f,
                skirtSway = pose.skirtSway * 0.4f
            )
        }
    }

    companion object {
        fun fromSaved(name: String?): HomeCharacter = entries.firstOrNull { it.name == name } ?: HITORI
    }
}
