package com.example.kennys_dokidoki_wallpaper

import android.content.Context

/** A project JSON and the assets it references are one selectable wallpaper artwork. */
internal data class ThreeJsArtwork(
    val id: String,
    val title: String,
    val caption: String,
    val projectAssetPath: String
)

internal object ThreeJsArtworkCatalog {
    const val KEY_SELECTED_ARTWORK = "selected_threejs_artwork_id"
    const val DEFAULT_ARTWORK_ID = "builtin_app_001"
    const val ENTRY_PAGE = "file:///android_asset/wallpaper/threejs/r110/index.html"

    private val builtInArtworks = listOf(
        ThreeJsArtwork(
            id = DEFAULT_ARTWORK_ID,
            title = "アート 001",
            caption = "001  ·  APP.JSON",
            projectAssetPath = "wallpaper/threejs/r110/app.json"
        )
    )

    fun all(): List<ThreeJsArtwork> = builtInArtworks

    fun find(id: String?): ThreeJsArtwork =
        builtInArtworks.firstOrNull { it.id == id } ?: builtInArtworks.first()

    fun selected(context: Context): ThreeJsArtwork {
        val id = context.getSharedPreferences(
            WallpaperRenderMode.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).getString(KEY_SELECTED_ARTWORK, DEFAULT_ARTWORK_ID)
        return find(id)
    }

    fun selectedId(context: Context): String = selected(context).id

    fun selectAsWallpaper(context: Context, artwork: ThreeJsArtwork) {
        context.getSharedPreferences(
            WallpaperRenderMode.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(KEY_SELECTED_ARTWORK, artwork.id)
            .putString(WallpaperRenderMode.KEY, WallpaperRenderMode.THREE_JS.preferenceValue)
            .apply()
    }
}
