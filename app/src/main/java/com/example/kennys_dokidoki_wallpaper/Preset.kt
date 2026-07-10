package com.example.kennys_dokidoki_wallpaper

import android.net.Uri

data class Preset(
    val id: String,
    var name: String,
    var category: String,
    val activePromptStates: Map<String, Int>, // cardId -> level
    val width: Int,
    val height: Int,
    val steps: Int,
    val batchCount: Int,
    val sampler: String,
    val randomEnabledCategories: Set<String>,
    var thumbnailUri: Uri? = null
)
