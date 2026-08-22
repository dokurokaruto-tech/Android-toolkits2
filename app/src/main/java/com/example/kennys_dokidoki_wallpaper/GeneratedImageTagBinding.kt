package com.example.kennys_dokidoki_wallpaper

/**
 * プロンプトビルダーで選んだカードのタグを、生成画像へ載せるための純関数。
 */
object GeneratedImageTagBinding {
    fun collect(cardTagSets: Iterable<Iterable<String>>): Set<String> {
        val result = linkedSetOf<String>()
        cardTagSets.forEach { tags ->
            tags.forEach { tag ->
                val trimmed = tag.trim()
                if (trimmed.isNotEmpty()) result.add(trimmed)
            }
        }
        return result
    }

    fun mergeForBrowse(existingDraftTags: Set<String>, generatedTags: Set<String>): Set<String> {
        return if (existingDraftTags.isNotEmpty()) existingDraftTags else generatedTags
    }
}
