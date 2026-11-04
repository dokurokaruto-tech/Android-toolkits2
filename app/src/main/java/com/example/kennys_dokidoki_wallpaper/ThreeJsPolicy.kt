package com.example.kennys_dokidoki_wallpaper

import kotlin.math.roundToInt
import kotlin.random.Random

enum class WallpaperSourceKind {
    IMAGES,
    THREE_JS;

    companion object {
        fun fromStored(raw: String?): WallpaperSourceKind {
            return entries.firstOrNull { it.name == raw } ?: IMAGES
        }
    }
}

data class ThreeJsWork(
    val id: String,
    val title: String,
    val assetPath: String
)

enum class TagSelectionState {
    ENABLED,
    LOCKED
}

data class RenderDimensions(
    val width: Int,
    val height: Int
)

/**
 * Built-in Three.js works shipped inside APK assets.
 *
 *   ImageSet(THREE_JS) ---> ThreeJsCatalog.BUILT_IN_WORKS
 *                                 |
 *                  +--------------+--------------+
 *                  |              |              |
 *               32.json        36.json        38.json
 */
object ThreeJsCatalog {
    const val ASSET_DIR = "threejs"
    const val VIRTUAL_HOST = "threejs.appassets.local"
    const val PLAYER_URL = "https://threejs.appassets.local/player.html"

    val BUILT_IN_WORKS: List<ThreeJsWork> = listOf(
        ThreeJsWork(
            id = "32",
            title = "Work 32",
            assetPath = "threejs/works/32.json"
        ),
        ThreeJsWork(
            id = "36",
            title = "Work 36",
            assetPath = "threejs/works/36.json"
        ),
        ThreeJsWork(
            id = "38",
            title = "Work 38",
            assetPath = "threejs/works/38.json"
        )
    )

    fun findById(id: String?): ThreeJsWork? {
        if (id.isNullOrBlank()) {
            return null
        }
        return BUILT_IN_WORKS.firstOrNull { it.id == id }
    }

    fun workOrDefault(id: String?): ThreeJsWork {
        return findById(id) ?: BUILT_IN_WORKS.first()
    }
}

object ThreeJsPolicy {
    const val DEFAULT_SET_BASE_NAME = "three.js"
    const val PREF_WORK_PREFIX = "last_threejs_work_for_album_"
    const val MAX_RENDER_EDGE_PX = 1600
    const val MIN_RENDER_EDGE_PX = 64
    private const val EVEN_ALIGNMENT_MASK = 1

    // Lock tag chips whenever Three.js mode is enabled on an ImageSet.
    fun tagSelectionStateFor(kind: WallpaperSourceKind): TagSelectionState {
        if (kind == WallpaperSourceKind.THREE_JS) {
            return TagSelectionState.LOCKED
        }
        return TagSelectionState.ENABLED
    }

    fun toggledKind(current: WallpaperSourceKind): WallpaperSourceKind {
        if (current == WallpaperSourceKind.THREE_JS) {
            return WallpaperSourceKind.IMAGES
        }
        return WallpaperSourceKind.THREE_JS
    }

    // Pick a random Three.js work, avoiding back-to-back repeats when 2+ works exist.
    fun pickNextRandomWork(
        works: List<ThreeJsWork>,
        currentId: String?,
        nextRandomInt: (Int) -> Int = { bound -> Random.nextInt(bound) }
    ): ThreeJsWork? {
        if (works.isEmpty()) {
            return null
        }
        if (works.size == 1) {
            return works.first()
        }

        val candidates = works.filter { it.id != currentId }.ifEmpty { works }
        val chosenIndex = nextRandomInt(candidates.size).coerceIn(0, candidates.lastIndex)
        return candidates[chosenIndex]
    }

    // Generate a non-colliding default set name such as "three.js" or "three.js (2)".
    fun suggestAutoSetName(
        existingNames: Set<String>,
        currentEditingName: String? = null
    ): String {
        val taken = if (currentEditingName.isNullOrBlank()) {
            existingNames
        } else {
            existingNames - currentEditingName
        }

        if (DEFAULT_SET_BASE_NAME !in taken) {
            return DEFAULT_SET_BASE_NAME
        }

        var suffix = 2
        while ("$DEFAULT_SET_BASE_NAME ($suffix)" in taken) {
            suffix++
        }
        return "$DEFAULT_SET_BASE_NAME ($suffix)"
    }

    fun prefKeyForAlbum(albumName: String): String {
        return "$PREF_WORK_PREFIX$albumName"
    }

    fun setItemCount(kind: WallpaperSourceKind, activeImageCount: Int): Int {
        if (kind == WallpaperSourceKind.THREE_JS) {
            return ThreeJsCatalog.BUILT_IN_WORKS.size
        }
        return activeImageCount
    }

    // Clamp offscreen render resolution to MAX_RENDER_EDGE_PX while preserving aspect ratio.
    fun computeRenderSize(surfaceWidth: Int, surfaceHeight: Int): RenderDimensions {
        val safeW = surfaceWidth.coerceAtLeast(MIN_RENDER_EDGE_PX)
        val safeH = surfaceHeight.coerceAtLeast(MIN_RENDER_EDGE_PX)
        val longestEdge = maxOf(safeW, safeH)

        if (longestEdge <= MAX_RENDER_EDGE_PX) {
            return RenderDimensions(alignEven(safeW), alignEven(safeH))
        }

        val scale = MAX_RENDER_EDGE_PX.toFloat() / longestEdge.toFloat()
        val scaledW = (safeW * scale).roundToInt()
        val scaledH = (safeH * scale).roundToInt()
        return RenderDimensions(alignEven(scaledW), alignEven(scaledH))
    }

    private fun alignEven(value: Int): Int {
        val clamped = value.coerceAtLeast(MIN_RENDER_EDGE_PX)
        return clamped and EVEN_ALIGNMENT_MASK.inv()
    }
}
