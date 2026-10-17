package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧中の画像のカード構成を、ビルダーの「今のセット」へ上書きする規則。
 * 復元方式は2つ。そのまま復元は当たりカードも固定選択にしランダマイザー分類を
 * オフへ上書きする。ランダマイザー再現は分類抽選をオンのまま残し、当たった
 * カードだけ選択を外して次回以降も抽選に任せる。
 */
object BuilderCardSetApplyPolicy {
    const val MISSING_CARDS = "この画像に使われたカードが分からない。新しく生成した画像なら残る。"
    const val EMPTY_CARDS = "この画像のカードはもう一枚も残っていない。"
    const val DIALOG_TITLE = "カードの復元方法"
    const val MODE_AS_IS_LABEL = "使われたカードをそのまま復元"
    const val MODE_RANDOMIZER_LABEL = "ランダマイザーのon/offも再現"

    /** 現存するカードだけ、選んだ復元方式の選択状態ごと。 */
    fun selectionFor(
        source: GeneratedImagePresetPolicy.Source,
        mode: GeneratedImagePresetPolicy.FromImageMode,
        availableCardIds: Set<String>
    ): Map<String, Int> = GeneratedImagePresetPolicy
        .cardsForMode(source, mode)
        .filterKeys { it in availableCardIds }

    /** 復元方式に応じたランダマイザー分類のオン状態。 */
    fun randomCategoriesFor(
        source: GeneratedImagePresetPolicy.Source,
        mode: GeneratedImagePresetPolicy.FromImageMode
    ): Set<String> = GeneratedImagePresetPolicy.randomCategoriesForMode(source, mode)

    fun requestedLabel(count: Int): String =
        "カード${count}枚を現在のセットへ上書き予約した。ビルダーへ戻ると反映される。"

    fun appliedLabel(cardCount: Int, randomCategories: Set<String>): String {
        val randomizer = if (randomCategories.isEmpty()) {
            "ランダマイザーはオフ"
        } else {
            "ランダマイザー${randomCategories.size}分類オン"
        }
        return "カード${cardCount}枚で現在のセットを上書きした（$randomizer）。"
    }
}
