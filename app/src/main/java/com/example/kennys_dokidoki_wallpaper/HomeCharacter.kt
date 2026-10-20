package com.example.kennys_dokidoki_wallpaper

/**
 * ホームの Live2D ステージに立つキャラクター。
 *
 * 全キャラが同じ 592x750 のリグを共有するため、顔パッチは
 * assets/live2d/<assetDir>/<prefix>_face_<key>.webp の規約で解決する。
 * ドラムのキットはコードで描画し、スティックの根元は hands の UV を使う。
 */
enum class HomeCharacter(
    val labelRes: Int,
    val crewName: String,
    val accent: Int,
    val assetDir: String,
    val instrument: Instrument,
    val headReaction: HeadReaction,
    /** 髪の揺れ倍率。ポニーテールは大きく、ボブは小さく */
    val hairSway: Float,
    /** 左手（画面左）のスティック根元 UV */
    val handLeftU: Float,
    val handLeftV: Float,
    /** 右手（画面右）のスティック根元 UV */
    val handRightU: Float,
    val handRightV: Float,
    /** ドラムシェルの色 */
    val shellColor: Int
) {
    HITORI(
        labelRes = R.string.character_hitori,
        crewName = "HITORI GOTOH",
        accent = 0xFFFF3E9D.toInt(),
        assetDir = "bocchi",
        instrument = Instrument.GUITAR,
        headReaction = HeadReaction.PANIC,
        hairSway = 1f,
        handLeftU = 0.335f,
        handLeftV = 0.615f,
        handRightU = 0.620f,
        handRightV = 0.500f,
        shellColor = 0xFFFF3E9D.toInt()
    ),
    KITA(
        labelRes = R.string.character_kita,
        crewName = "IKUYO KITA",
        accent = 0xFFFF635C.toInt(),
        assetDir = "kita",
        instrument = Instrument.GUITAR,
        headReaction = HeadReaction.CHEER,
        hairSway = 0.68f,
        handLeftU = 0.300f,
        handLeftV = 0.560f,
        handRightU = 0.640f,
        handRightV = 0.480f,
        shellColor = 0xFFFF635C.toInt()
    ),
    NIJIKA(
        labelRes = R.string.character_nijika,
        crewName = "NIJIKA IJICHI",
        accent = 0xFFFFC94A.toInt(),
        assetDir = "nijika",
        instrument = Instrument.DRUMS,
        headReaction = HeadReaction.CHEER,
        hairSway = 1.15f,
        handLeftU = BocchiLive2dPolicy.KIT_HAT_U + 0.06f,
        handLeftV = 0.46f,
        handRightU = BocchiLive2dPolicy.KIT_SNARE_U + 0.14f,
        handRightV = 0.44f,
        shellColor = 0xFFFFC94A.toInt()
    ),
    RYO(
        labelRes = R.string.character_ryo,
        crewName = "RYO YAMADA",
        accent = 0xFF02D1E0.toInt(),
        assetDir = "ryo",
        instrument = Instrument.GUITAR,
        headReaction = HeadReaction.PANIC,
        hairSway = 0.34f,
        handLeftU = 0.300f,
        handLeftV = 0.560f,
        handRightU = 0.640f,
        handRightV = 0.480f,
        shellColor = 0xFF02D1E0.toInt()
    );

    val bodyAsset: String
        get() = "live2d/$assetDir/${assetDir}_body_base.webp"

    fun faceAsset(expression: BocchiExpression): String? {
        val key = expression.key ?: return null
        return "live2d/$assetDir/${assetDir}_face_$key.webp"
    }

    /** 物理エンジンへ渡す前の、キャラクター固有の姿勢補正 */
    fun adaptPose(pose: BocchiPose): BocchiPose {
        if (this == HITORI) {
            return pose
        }
        return pose.copy(
            ahoge = if (this == NIJIKA) pose.ahoge else 0f,
            cubeSwing = 0f,
            glitchIntensity = 0f,
            hairSideMid = pose.hairSideMid * hairSway,
            hairSideTip = pose.hairSideTip * hairSway,
            armLeft = if (instrument == Instrument.DRUMS) pose.armLeft else 0f
        )
    }

    companion object {
        fun fromSaved(name: String?): HomeCharacter = entries.firstOrNull { it.name == name } ?: HITORI
    }
}
