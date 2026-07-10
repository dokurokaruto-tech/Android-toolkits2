package com.example.kennys_dokidoki_wallpaper

sealed class TagListItem {
    data class Header(val categoryIndex: Int, val name: String) : TagListItem()
    data class TagItem(val categoryIndex: Int, val tagIndex: Int, val name: String) : TagListItem()
}
