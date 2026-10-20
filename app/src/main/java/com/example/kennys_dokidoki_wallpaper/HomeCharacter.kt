package com.example.kennys_dokidoki_wallpaper

/**
 * ホームのステージに立つ結束バンドのメンバー。
 *
 *   素材 : 592×750 の共通リグへ位置合わせ済み。Cubismモデルではない。
 *   表情 : 顔パッチを差し替える。無い表情は fallback で近い表情へ寄せる。
 *   演出 : effect がステージのパーティクルとHUDの文言を決める。
 */
enum class HomeCharacter(
    val labelRes: Int,
    val crewName: String,
    val accent: Int,
    val bodyAsset: String,
    val facePrefix: String,
    val effect: StageEffect,
    val motion: MotionStyle,
    val hudTag: String,
    private val faces: Map<BocchiExpression, String>,
    private val fallback: Map<BocchiExpression, BocchiExpression>
) {
    HITORI(
        labelRes = R.string.character_hitori,
        crewName = "HITORI GOTOH",
        accent = 0xFFFF3E9D.toInt(),
        bodyAsset = BocchiLive2dPolicy.BODY_ASSET,
        facePrefix = "live2d/bocchi/bocchi_face_",
        effect = StageEffect.CYBER_CUBE,
        motion = MotionStyle.HITORI,
        hudTag = "GUITAR HERO",
        faces = mapOf(
            BocchiExpression.HALF_BLINK to "half_blink",
            BocchiExpression.BLINK_CLOSED to "blink",
            BocchiExpression.HAPPY_SMILE to "smile",
            BocchiExpression.AWAKENED_GROOVE to "awakened",
            BocchiExpression.BOCCHI_PANIC to "panic"
        ),
        fallback = mapOf(
            BocchiExpression.LAUGH_JOY to BocchiExpression.HAPPY_SMILE,
            BocchiExpression.SURPRISED to BocchiExpression.BOCCHI_PANIC,
            BocchiExpression.WINK to BocchiExpression.HAPPY_SMILE
        )
    ),
    KITA(
        labelRes = R.string.character_kita,
        crewName = "IKUYO KITA",
        accent = 0xFFFF635C.toInt(),
        bodyAsset = "live2d/kita/kita_body_base.webp",
        facePrefix = "live2d/kita/kita_face_",
        effect = StageEffect.KITA_SUNSHINE,
        motion = MotionStyle.KITA,
        hudTag = "KITA~N!",
        faces = mapOf(
            BocchiExpression.HALF_BLINK to "half_blink",
            BocchiExpression.BLINK_CLOSED to "blink",
            BocchiExpression.HAPPY_SMILE to "smile",
            BocchiExpression.AWAKENED_GROOVE to "awakened",
            BocchiExpression.LAUGH_JOY to "laugh",
            BocchiExpression.SURPRISED to "surprised",
            BocchiExpression.WINK to "wink"
        ),
        fallback = mapOf(BocchiExpression.BOCCHI_PANIC to BocchiExpression.SURPRISED)
    ),
    NIJIKA(
        labelRes = R.string.character_nijika,
        crewName = "NIJIKA IJICHI",
        accent = 0xFFFFC24B.toInt(),
        bodyAsset = "live2d/nijika/nijika_body_base.webp",
        facePrefix = "live2d/nijika/nijika_face_",
        effect = StageEffect.NIJIKA_DRUMS,
        motion = MotionStyle.NIJIKA,
        hudTag = "SHIMOKITA ANGEL",
        faces = mapOf(
            BocchiExpression.HALF_BLINK to "half_blink",
            BocchiExpression.BLINK_CLOSED to "blink",
            BocchiExpression.HAPPY_SMILE to "smile",
            BocchiExpression.AWAKENED_GROOVE to "awakened",
            BocchiExpression.LAUGH_JOY to "laugh",
            BocchiExpression.SURPRISED to "surprised",
            BocchiExpression.WINK to "wink"
        ),
        fallback = mapOf(BocchiExpression.BOCCHI_PANIC to BocchiExpression.LAUGH_JOY)
    ),
    RYO(
        labelRes = R.string.character_ryo,
        crewName = "RYO YAMADA",
        accent = 0xFF6FA8FF.toInt(),
        bodyAsset = "live2d/ryo/ryo_body_base.webp",
        facePrefix = "live2d/ryo/ryo_face_",
        effect = StageEffect.RYO_MOONLIGHT,
        motion = MotionStyle.RYO,
        hudTag = "COOL BASSIST",
        faces = mapOf(
            BocchiExpression.HALF_BLINK to "half_blink",
            BocchiExpression.BLINK_CLOSED to "blink",
            BocchiExpression.HAPPY_SMILE to "smile",
            BocchiExpression.AWAKENED_GROOVE to "awakened",
            BocchiExpression.LAUGH_JOY to "laugh",
            BocchiExpression.SURPRISED to "surprised",
            BocchiExpression.WINK to "wink"
        ),
        fallback = mapOf(BocchiExpression.BOCCHI_PANIC to BocchiExpression.SURPRISED)
    );

    val rig: CharacterRig get() = CharacterRigs.of(this)

    /** 実際に読み込む顔パッチ。SHY_BASE はボディ素体をそのまま使う。 */
    fun faceAsset(expression: BocchiExpression): String? {
        var current: BocchiExpression? = expression
        var hops = 0
        while (current != null && hops <= MAX_FALLBACK_HOPS) {
            val suffix = faces[current]
            if (suffix != null) {
                return "$facePrefix$suffix.webp"
            }
            current = fallback[current]
            hops++
        }
        return null
    }

    /** その表情の素材があるか。無ければボディの素の表情を描く。 */
    fun hasFace(expression: BocchiExpression): Boolean = faceAsset(expression) != null

    /** 素材が無い表情を、実際に描ける表情へ寄せる。 */
    fun fallbackExpression(expression: BocchiExpression): BocchiExpression {
        var current = expression
        repeat(MAX_FALLBACK_HOPS) {
            fallback[current]?.let { return it }
            current = fallback[current] ?: return current
        }
        return current
    }

    /** 部位ごとの反応は物理エンジンが motion に応じて切り替える。 */
    fun tapZone(zone: BocchiHitZone): BocchiHitZone = zone

    companion object {
        private const val MAX_FALLBACK_HOPS = 3

        fun fromSaved(name: String?): HomeCharacter = entries.firstOrNull { it.name == name } ?: HITORI
    }
}

/** ステージ演出の種類。HUDとパーティクルの色を切り替える。 */
enum class StageEffect {
    CYBER_CUBE,
    KITA_SUNSHINE,
    NIJIKA_DRUMS,
    RYO_MOONLIGHT
}
