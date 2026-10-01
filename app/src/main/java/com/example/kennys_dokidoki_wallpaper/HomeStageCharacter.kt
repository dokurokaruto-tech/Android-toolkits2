package com.example.kennys_dokidoki_wallpaper

import android.content.Context

internal const val HOME_BOCCHI_ACCENT_COLOR = 0xFFFF3E9D.toInt()
internal const val HOME_KITA_ACCENT_COLOR = 0xFFFF765C.toInt()

internal enum class HomeStageCharacter(
    val labelRes: Int,
    val accentColor: Int
) {
    BOCCHI(R.string.home_character_bocchi, HOME_BOCCHI_ACCENT_COLOR),
    KITA(R.string.home_character_kita, HOME_KITA_ACCENT_COLOR)
}

internal object HomeStageCharacterPolicy {

    fun next(character: HomeStageCharacter): HomeStageCharacter = when (character) {
        HomeStageCharacter.BOCCHI -> HomeStageCharacter.KITA
        HomeStageCharacter.KITA -> HomeStageCharacter.BOCCHI
    }

    fun fromKey(key: String?): HomeStageCharacter =
        HomeStageCharacter.entries.firstOrNull { it.name == key } ?: HomeStageCharacter.BOCCHI
}

internal object HomeStageCharacterStore {

    private const val PREFS_NAME = "home_carousel"
    private const val KEY_CHARACTER = "stage_character"

    fun load(context: Context): HomeStageCharacter {
        val key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CHARACTER, null)
        return HomeStageCharacterPolicy.fromKey(key)
    }

    fun save(context: Context, character: HomeStageCharacter) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CHARACTER, character.name)
            .apply()
    }
}
