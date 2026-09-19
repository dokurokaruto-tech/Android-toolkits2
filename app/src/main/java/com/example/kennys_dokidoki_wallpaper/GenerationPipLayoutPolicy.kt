package com.example.kennys_dokidoki_wallpaper

import kotlin.math.pow

/**
 * PiP ウインドウの形は「いま生成中の画像の縦横比」で決める。
 * タップで大きくするときも縦長は縦長のまま、横長は横長のまま広げる。
 * 比率は常に明示する。比率なしの更新は端末既定（横長になりがち）へ
 * リセットされるため、それが「縦長画像なのに横長になる」原因だった。
 */
object GenerationPipLayoutPolicy {

    /** Android が PiP のアスペクト比（width / height）として受け付ける範囲。 */
    const val SYSTEM_MAX_RATIO = 2.39f
    const val SYSTEM_MIN_RATIO = 1f / SYSTEM_MAX_RATIO

    /** 範囲端の正確な分数。丸め誤差で端をまたがないための判定に使う。 */
    private val MIN_FRACTION = Ratio(100, 239)
    private val MAX_FRACTION = Ratio(239, 100)

    /** 画像がまだ届いていないときの初期形。生成の既定（ポートレート）に合わせる。 */
    const val FALLBACK_WIDTH = 9
    const val FALLBACK_HEIGHT = 16

    /** 拡大は比を 1 へ寄せる（0.5 乗）。1 を跨がないので向きは不変。 */
    private const val ENLARGE_EXPONENT = 0.5f

    private const val FRACTION_SCALE = 1000

    /** Rational(width, height) に渡す既約分数。 */
    data class Ratio(val width: Int, val height: Int) {
        val value: Float get() = width.toFloat() / height.toFloat()
        val isPortrait: Boolean get() = width < height
        val isLandscape: Boolean get() = width > height
    }

    fun windowRatio(imageWidth: Int, imageHeight: Int, enlarged: Boolean): Ratio {
        if (imageWidth <= 0 || imageHeight <= 0) return Ratio(FALLBACK_WIDTH, FALLBACK_HEIGHT)
        val raw = imageWidth.toFloat() / imageHeight.toFloat()
        val shaped = clampKeepingOrientation(raw).let { if (enlarged) enlarge(it) else it }
        return toFraction(shaped)
    }

    /** 縦は縦の範囲、横は横の範囲へ丸める。正方形は正方形のまま。 */
    private fun clampKeepingOrientation(raw: Float): Float = when {
        raw < 1f -> raw.coerceIn(SYSTEM_MIN_RATIO, 1f)
        else -> raw.coerceIn(1f, SYSTEM_MAX_RATIO)
    }

    /** 比を ENLARGE_EXPONENT 乗する。1 を跨がないので向きは不変、面積は増える。 */
    private fun enlarge(ratio: Float): Float = ratio.pow(ENLARGE_EXPONENT)

    private fun toFraction(ratio: Float): Ratio {
        val num = Math.round(ratio * FRACTION_SCALE).coerceAtLeast(1)
        val den = FRACTION_SCALE
        // 丸めでシステム範囲の外へ出ると端末既定に戻されるので、内側へ押し戻す。
        val bounded = when {
            num * MIN_FRACTION.height < MIN_FRACTION.width * den ->
                (MIN_FRACTION.width * den + MIN_FRACTION.height - 1) / MIN_FRACTION.height
            num * MAX_FRACTION.height > MAX_FRACTION.width * den ->
                MAX_FRACTION.width * den / MAX_FRACTION.height
            else -> num
        }
        val divisor = gcd(bounded, den)
        return Ratio(bounded / divisor, den / divisor)
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val next = x % y
            x = y
            y = next
        }
        return x.coerceAtLeast(1)
    }
}
