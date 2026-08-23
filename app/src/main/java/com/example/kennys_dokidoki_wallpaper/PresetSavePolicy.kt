package com.example.kennys_dokidoki_wallpaper

/**
 * プリセット保存／編集の初期値と、選択の取り込み方。
 */
object PresetSavePolicy {
    const val QUICK_CATEGORY = "クイックプリセット"
    const val OVERWRITE_BUTTON_LABEL = "選択中のカードで上書き"
    const val ADD_CATEGORY_LABEL = "分類を追加"
    const val FROM_IMAGE_MENU_LABEL = "この構成をプリセットに"
    const val FROM_IMAGE_INDIVIDUAL_LABEL = "当たったカードも固定する"
    const val FROM_IMAGE_RANDOMIZER_LABEL = "ランダマイザーを残す"
    const val FROM_IMAGE_INDIVIDUAL_DETAIL = "この画像どおり全部を個別選択にする。"
    const val FROM_IMAGE_RANDOMIZER_DETAIL = "当たりを外し、ランダマイザーONだけ残す。"

    fun defaultName(width: Int, height: Int): String = "$width x $height"

    fun selectableCategories(existing: Collection<String>): List<String> {
        val seen = linkedSetOf<String>()
        seen.add(QUICK_CATEGORY)
        existing.map { it.trim() }.filter { it.isNotEmpty() }.forEach { seen.add(it) }
        return seen.toList()
    }

    fun defaultCategory(existing: Collection<String> = emptyList()): String {
        val categories = selectableCategories(existing)
        return if (QUICK_CATEGORY in categories) QUICK_CATEGORY else categories.first()
    }

    fun overwriteSelection(
        selectionLevels: Map<String, Int>,
        randomEnabledCategories: Set<String>
    ): Pair<Map<String, Int>, Set<String>> {
        return selectionLevels.toMap() to randomEnabledCategories.toSet()
    }
}
