package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences

/**
 * OpenRouterモデルのお気に入り登録。
 * モデルIDのSetとしてsettingsに保存する。チャット画面とJevピッカーで共有。
 */
internal object ModelFavorites {
    private const val KEY = "openrouter_favorite_models"

    fun load(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty()

    /** 星クリックで登録/解除を切り替え、保存後の最新Setを返す。 */
    fun toggle(prefs: SharedPreferences, modelId: String): Set<String> {
        val next = load(prefs).toMutableSet()
        if (!next.remove(modelId)) {
            next.add(modelId)
        }
        prefs.edit().putStringSet(KEY, next).apply()
        return next
    }
}

/** モデル一覧のフィルタチップの状態。「すべて」「無料」「お気に入り」。 */
internal enum class ModelListFilter {
    ALL, FREE, FAVORITE
}
