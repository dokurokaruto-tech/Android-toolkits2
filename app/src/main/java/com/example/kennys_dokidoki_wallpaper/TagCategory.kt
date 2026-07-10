package com.example.kennys_dokidoki_wallpaper

data class TagCategory(
    var name: String,
    val tags: MutableList<String> = mutableListOf(),
    var parentCategoryName: String? = null // 親カテゴリー（統一規格の設定用）
)
