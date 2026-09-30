package com.example.kennys_dokidoki_wallpaper

/**
 * カルーセルホームに並ぶ機能。1機能 = 1アイコン = 1色。
 * 機能を増やすときはここへ1行足すだけでよく、既存の並び順の後ろへ自動で追加される。
 *
 * navId が null の機能は MainActivity の一画面ではなく、専用の Activity を開く。
 */
enum class HomeSection(
    val label: String,
    val iconRes: Int,
    val containerColorRes: Int,
    val iconColorRes: Int,
    val navId: Int?
) {
    ALL_IMAGES(
        "全画像", R.drawable.ic_home_all_images,
        R.color.home_all_images_container, R.color.home_all_images_icon, R.id.nav_all_images
    ),
    IMAGE_SETS(
        "イメージセット", R.drawable.ic_home_image_sets,
        R.color.home_image_sets_container, R.color.home_image_sets_icon, R.id.nav_sets
    ),
    TAGS(
        "タグ", R.drawable.ic_home_tags,
        R.color.home_tags_container, R.color.home_tags_icon, R.id.nav_tag_prompts
    ),
    BUILDER(
        "画像生成", R.drawable.ic_home_builder,
        R.color.home_builder_container, R.color.home_builder_icon, R.id.nav_builder
    ),
    DIARY(
        "日記", R.drawable.ic_home_diary,
        R.color.home_diary_container, R.color.home_diary_icon, null
    ),
    SETTINGS(
        "設定", R.drawable.ic_home_settings,
        R.color.home_settings_container, R.color.home_settings_icon, R.id.nav_settings
    );

    companion object {
        const val EXTRA_KEY = "HOME_SECTION"

        fun of(key: String?): HomeSection = entries.firstOrNull { it.name == key } ?: ALL_IMAGES
    }
}
