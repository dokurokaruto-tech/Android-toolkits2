package com.example.kennys_dokidoki_wallpaper

/**
 * three.js イメージセットの純粋ロジック。Android 非依存で JUnit 検証できる。
 *
 *   threeJsEnabled = true のセット
 *     ├─ タグ選択は無効（作品は組み込み目録からのみ供給）
 *     ├─ 画像一致検索は常に空（壁紙プールは ThreeJsWork）
 *     └─ 「次の壁紙」ジェスチャーは現在と異なる作品をランダム選択
 *
 * セット実体への参照は持たない。判定は ImageSet?.isThreeJs 拡張で行う。
 */
object ThreeJsSetPolicy {

    const val DEFAULT_SET_NAME = "three.js"

    // three.js セットはタグを条件にしない。エディタのタグ欄をロックする判定。
    fun locksTags(threeJsEnabled: Boolean): Boolean = threeJsEnabled

    // 画像0枚警告は three.js セットには適用しない（作品が常に存在する）。
    fun needsImageMatch(threeJsEnabled: Boolean): Boolean = !threeJsEnabled

    /**
     * 次の作品インデックスをランダムに選ぶ。2作品以上あれば現在と必ず異なる。
     *
     * randomInReducedRange は [0, count-2] の一様乱数。current を飛ばすよう写像する。
     * 例: count=3, current=1 のとき 0->0, 1->2 （1 は選ばれない）。
     */
    fun nextIndex(current: Int, count: Int, randomInReducedRange: Int): Int {
        if (count <= 1) {
            return 0
        }
        val pick = randomInReducedRange.coerceIn(0, count - 2)
        return if (pick >= current) pick + 1 else pick
    }
}
