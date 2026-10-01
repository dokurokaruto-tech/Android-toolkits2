package com.example.kennys_dokidoki_wallpaper

/** 素材は共通の592×750リグへ位置合わせ済み。Cubismモデルではない。 */
enum class HomeCharacter(
    val labelRes: Int,
    val crewName: String,
    val accent: Int,
    val bodyAsset: String
) {
    HITORI(R.string.character_hitori, "HITORI GOTOH", 0xFFFF3E9D.toInt(), BocchiLive2dPolicy.BODY_ASSET),
    KITA(R.string.character_kita, "IKUYO KITA", 0xFFFF635C.toInt(), "live2d/kita/kita_body_base.webp");

    fun faceAsset(expression: BocchiExpression): String? {
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
        }
        return "live2d/kita/kita_face_$suffix.webp"
    }

    fun tapZone(zone: BocchiHitZone): BocchiHitZone {
        if (this == KITA && zone == BocchiHitZone.HEAD_PANIC) {
            return BocchiHitZone.GUITAR_STRUM
        }
        return zone
    }

    fun adaptPose(pose: BocchiPose): BocchiPose {
        if (this == HITORI) {
            return pose
        }
        return pose.copy(
            ahoge = 0f,
            cubeSwing = 0f,
            glitchIntensity = 0f,
            hairSideMid = pose.hairSideMid * 0.65f,
            hairSideTip = pose.hairSideTip * 0.55f
        )
    }

    companion object {
        fun fromSaved(name: String?): HomeCharacter = entries.firstOrNull { it.name == name } ?: HITORI
    }
}
