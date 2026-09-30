package com.example.kennys_dokidoki_wallpaper

/**
 * カルーセルホームに並ぶ機能。並び順がそのままカルーセルの左→右になる。
 * 例: 先頭が「全画像」、右へスワイプすると「イメージセット」。
 */
enum class HomeSection(
    val label: String,
    val iconRes: Int,
    val navId: Int
) {
    ALL_IMAGES("全画像", R.drawable.ic_md3_photo_library, R.id.nav_all_images),
    IMAGE_SETS("イメージセット", R.drawable.ic_md3_gallery, R.id.nav_sets),
    TAGS("タグ", R.drawable.ic_md3_description, R.id.nav_tag_prompts),
    BUILDER("画像生成", R.drawable.ic_md3_auto_awesome, R.id.nav_builder),
    SETTINGS("設定", R.drawable.ic_settings, R.id.nav_settings);

    companion object {
        const val EXTRA_KEY = "HOME_SECTION"

        fun of(key: String?): HomeSection = entries.firstOrNull { it.name == key } ?: ALL_IMAGES
    }
}
