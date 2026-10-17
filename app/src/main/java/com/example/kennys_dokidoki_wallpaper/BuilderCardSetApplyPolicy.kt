package com.example.kennys_dokidoki_wallpaper

/**
 * 閲覧中の画像のカード構成を、ビルダーの「今のセット」へ上書きする規則。
 * ランダマイザーで当たったカードも固定選択にし、ランダマイザー分類は空へ
 * 上書きする。次回の生成がこの画像と同じ構成になるようにする。
 */
object BuilderCardSetApplyPolicy {
    const val MISSING_CARDS = "この画像に使われたカードが分からない。新しく生成した画像なら残る。"
    const val EMPTY_CARDS = "この画像のカードはもう一枚も残っていない。"

    /** 現存するカードだけ、使われたときのレベルごと。 */
    fun selectionFor(
        source: GeneratedImagePresetPolicy.Source,
        availableCardIds: Set<String>
    ): Map<String, Int> = GeneratedImagePresetPolicy
        .cardsForMode(source, GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS)
        .filterKeys { it in availableCardIds }

    /** ランダマイザー分類は常に空（オフ）で上書きする。 */
    fun randomCategoriesFor(source: GeneratedImagePresetPolicy.Source): Set<String> =
        GeneratedImagePresetPolicy.randomCategoriesForMode(
            source,
            GeneratedImagePresetPolicy.FromImageMode.INDIVIDUAL_CARDS
        )

    fun requestedLabel(count: Int): String =
        "カード${count}枚を現在のセットへ上書き予約した。ビルダーへ戻ると反映される。"

    fun appliedLabel(count: Int): String =
        "カード${count}枚で現在のセットを上書きした（ランダマイザーはオフ）。"
}
