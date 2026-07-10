package com.example.kennys_dokidoki_wallpaper

import android.net.Uri

data class PromptCard(
    val id: String,
    var label: String,
    var mainPrompt: String,
    var negativePrompt: String,
    var thumbnailUri: Uri? = null,
    var category: String = "未分類",
    val appliedTags: MutableSet<String> = mutableSetOf(),
    var useIndividualRandomizer: Boolean = false,
    var randomizerProbability: Int = 50 // 0 to 100
)
