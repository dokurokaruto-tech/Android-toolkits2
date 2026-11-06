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

enum class ThreeJsWorkOrigin {
    BUILT_IN,
    USER;

    companion object {
        fun fromStored(raw: String?): ThreeJsWorkOrigin {
            return entries.firstOrNull { it.name == raw } ?: USER
        }
    }
}

data class ThreeJsWork(
    val id: String,
    val title: String,
    val assetPath: String?,
    val dateLabel: String = "",
    val origin: ThreeJsWorkOrigin = ThreeJsWorkOrigin.BUILT_IN,
    val updatedAtMs: Long = 0L
)

enum class TagSelectionState {
    ENABLED,
    LOCKED
}

enum class ThreeJsDisplayState {
    NOT_SHOWING,
    SHOWING_FRAME
}

enum class ThreeJsLoadTransition {
    FADE_FROM_BLACK,
    IMMEDIATE_IN_PLACE
}

enum class ThreeJsFadePhase {
    WAITING_FIRST_FRAME,
    FADING_IN,
    OPAQUE
}

data class RenderDimensions(
    val width: Int,
    val height: Int
)

/**
 * Built-in Three.js works shipped inside APK assets as the initial seed catalog.
 *
 *   ImageSet(THREE_JS) ---> ThreeJsWorkStore ---> Built-in + User Works
 *                                                       |
 *                                        +--------------+--------------+
 *                                        |              |              |
 *                                     32.json        36.json        38.json
 */
object ThreeJsCatalog {
    const val ASSET_DIR = "threejs"
    const val VIRTUAL_HOST = "threejs.appassets.local"
    const val PLAYER_URL = "https://threejs.appassets.local/player.html"
    const val CAPTURE_URL = "https://threejs.appassets.local/player.html?capture=1"

    val BUILT_IN_WORKS: List<ThreeJsWork> = listOf(
        ThreeJsWork(
            id = "32",
            title = "032",
            assetPath = "threejs/works/32.json",
            dateLabel = "19 12 28",
            origin = ThreeJsWorkOrigin.BUILT_IN
        ),
        ThreeJsWork(
            id = "36",
            title = "036",
            assetPath = "threejs/works/36.json",
            dateLabel = "20 01 04",
            origin = ThreeJsWorkOrigin.BUILT_IN
        ),
        ThreeJsWork(
            id = "38",
            title = "038",
            assetPath = "threejs/works/38.json",
            dateLabel = "21 03 10",
            origin = ThreeJsWorkOrigin.BUILT_IN
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
    const val INITIAL_FADE_MS = 300L
    private const val EVEN_ALIGNMENT_MASK = 1

    fun resolveLoadTransition(displayState: ThreeJsDisplayState): ThreeJsLoadTransition {
        if (displayState == ThreeJsDisplayState.SHOWING_FRAME) {
            return ThreeJsLoadTransition.IMMEDIATE_IN_PLACE
        }
        return ThreeJsLoadTransition.FADE_FROM_BLACK
    }

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

    fun setItemCount(
        kind: WallpaperSourceKind,
        activeImageCount: Int,
        threeWorkCount: Int
    ): Int {
        if (kind == WallpaperSourceKind.THREE_JS) {
            return threeWorkCount
        }
        return activeImageCount
    }

    // Format gallery caption in the understated "038 ~ 21 03 10" style.
    fun formatTileCaption(displayOrder: Int, work: ThreeJsWork): String {
        val label = work.title.trim().ifEmpty {
            displayOrder.coerceAtLeast(1).toString().padStart(3, '0')
        }
        val sub = work.dateLabel.trim()
        if (sub.isEmpty()) {
            return label
        }
        return "$label ~ $sub"
    }

    // Clamp offscreen render resolution to maxEdgePx while preserving aspect ratio.
    fun computeRenderSize(
        surfaceWidth: Int,
        surfaceHeight: Int,
        maxEdgePx: Int = MAX_RENDER_EDGE_PX
    ): RenderDimensions {
        val capEdge = maxEdgePx.coerceIn(MIN_RENDER_EDGE_PX, MAX_RENDER_EDGE_PX)
        val safeW = surfaceWidth.coerceAtLeast(MIN_RENDER_EDGE_PX)
        val safeH = surfaceHeight.coerceAtLeast(MIN_RENDER_EDGE_PX)
        val longestEdge = maxOf(safeW, safeH)

        if (longestEdge <= capEdge) {
            return RenderDimensions(alignEven(safeW), alignEven(safeH))
        }

        val scale = capEdge.toFloat() / longestEdge.toFloat()
        val scaledW = (safeW * scale).roundToInt()
        val scaledH = (safeH * scale).roundToInt()
        return RenderDimensions(alignEven(scaledW), alignEven(scaledH))
    }

    private fun alignEven(value: Int): Int {
        val clamped = value.coerceAtLeast(MIN_RENDER_EDGE_PX)
        return clamped and EVEN_ALIGNMENT_MASK.inv()
    }
}
