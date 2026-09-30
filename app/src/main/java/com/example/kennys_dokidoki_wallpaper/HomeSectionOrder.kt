package com.example.kennys_dokidoki_wallpaper

import android.content.Context

/**
 * カルーセルに並べる順番の保存先。
 * 保存済みの並びを尊重しつつ、あとから増えた機能は末尾へ自動で足す。
 *
 *   保存: [DIARY, ALL_IMAGES]  +  新機能 TAGS  ->  [DIARY, ALL_IMAGES, TAGS]
 */
object HomeSectionOrder {

    private const val PREFS_NAME = "home_carousel"
    private const val KEY_ORDER = "section_order"
    private const val SEPARATOR = ","

    /** 保存名のリストと実在する機能を突き合わせる。消えた機能は捨て、増えた機能は末尾へ */
    fun merge(savedNames: List<String>, all: List<HomeSection>): List<HomeSection> {
        val known = all.associateBy { it.name }
        val ordered = savedNames.mapNotNull { known[it] }.distinct()
        val missing = all.filterNot { ordered.contains(it) }
        return ordered + missing
    }

    fun load(context: Context): List<HomeSection> {
        val raw = prefs(context).getString(KEY_ORDER, null).orEmpty()
        val savedNames = raw.split(SEPARATOR).filter { it.isNotBlank() }
        return merge(savedNames, HomeSection.entries)
    }

    fun save(context: Context, order: List<HomeSection>) {
        prefs(context).edit()
            .putString(KEY_ORDER, order.joinToString(SEPARATOR) { it.name })
            .apply()
    }

    /** 並べ替えUIのドラッグ結果。from を取り出して to へ差し込む */
    fun move(order: List<HomeSection>, from: Int, to: Int): List<HomeSection> {
        if (from !in order.indices || to !in order.indices || from == to) {
            return order
        }
        val moved = order.toMutableList()
        moved.add(to, moved.removeAt(from))
        return moved
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
