package com.example.kennys_dokidoki_wallpaper

import android.content.Context

object HomeCharacterStore {
    private const val PREFS_NAME = "home_carousel"
    private const val KEY_CHARACTER = "stage_character"

    fun load(context: Context): HomeCharacter = HomeCharacter.fromSaved(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_CHARACTER, null)
    )

    fun save(context: Context, character: HomeCharacter) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CHARACTER, character.name).apply()
    }
}
