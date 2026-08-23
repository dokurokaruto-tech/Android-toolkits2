package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import java.util.UUID

/**
 * 生成画像から「その画像に使われたカードと同じ」プリセットを組み立てる。
 */
object GeneratedImagePresetPolicy {
    data class Source(
        val cardStates: Map<String, Int>,
        val width: Int,
        val height: Int,
        val steps: Int,
        val sampler: String,
        val thumbnail: String
    )

    data class InferableCard(
        val id: String,
        val appliedTags: Set<String>,
        val mainPrompt: String = ""
    )

    fun inferCardStates(
        imageTags: Set<String>,
        roster: Collection<InferableCard>
    ): Map<String, Int> {
        val tags = imageTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (tags.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, Int>()
        roster.forEach { card ->
            val cardTags = card.appliedTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (cardTags.isNotEmpty() && tags.containsAll(cardTags)) {
                result[card.id] = 1
            }
        }
        return result
    }

    /**
     * 完成プロンプトからカードと選択強度を復元する。
     * 長いプロンプトから順に食い、部分一致で別カードを誤認しない。
     */
    fun inferCardStatesFromPrompt(
        prompt: String,
        roster: Collection<InferableCard>
    ): Map<String, Int> {
        var remaining = prompt
        if (remaining.isBlank()) return emptyMap()
        val result = linkedMapOf<String, Int>()
        roster.filter { it.mainPrompt.trim().isNotEmpty() }
            .sortedByDescending { it.mainPrompt.trim().length }
            .forEach { card ->
                val text = card.mainPrompt.trim()
                val weighted3 = "($text:1.6)"
                val weighted2 = "($text:1.2)"
                val level = when {
                    remaining.contains(weighted3) -> 3
                    remaining.contains(weighted2) -> 2
                    remaining.contains(text) -> 1
                    else -> return@forEach
                }
                val token = when (level) {
                    3 -> weighted3
                    2 -> weighted2
                    else -> text
                }
                remaining = remaining.replaceFirst(token, "")
                result[card.id] = level
            }
        return result
    }

    fun resolveCardStates(
        stored: Map<String, Int>,
        imageTags: Set<String>,
        roster: Collection<InferableCard>,
        prompt: String? = null
    ): Map<String, Int> {
        val kept = stored.filter { it.key.isNotBlank() && it.value in 1..3 }
        if (kept.isNotEmpty()) return kept
        val fromPrompt = inferCardStatesFromPrompt(prompt.orEmpty(), roster)
        if (fromPrompt.isNotEmpty()) return fromPrompt
        return inferCardStates(imageTags, roster)
    }

    fun sourceFrom(
        storedCards: Map<String, Int>,
        imageTags: Set<String>,
        roster: Collection<InferableCard>,
        width: Int?,
        height: Int?,
        steps: Int?,
        sampler: String?,
        thumbnail: String,
        prompt: String? = null
    ): Source? {
        val cards = resolveCardStates(storedCards, imageTags, roster, prompt)
        if (cards.isEmpty()) return null
        return Source(
            cardStates = cards,
            width = width?.takeIf { it > 0 } ?: 720,
            height = height?.takeIf { it > 0 } ?: 1280,
            steps = steps?.takeIf { it > 0 } ?: 20,
            sampler = sampler?.takeIf { it.isNotBlank() } ?: "Euler a",
            thumbnail = thumbnail
        )
    }

    fun buildPreset(source: Source, id: String = UUID.randomUUID().toString()): Preset {
        return Preset(
            id = id,
            name = PresetSavePolicy.defaultName(source.width, source.height),
            category = PresetSavePolicy.QUICK_CATEGORY,
            activePromptStates = source.cardStates,
            width = source.width,
            height = source.height,
            steps = source.steps,
            batchCount = 1,
            sampler = source.sampler,
            randomEnabledCategories = emptySet(),
            thumbnailUri = source.thumbnail.takeIf { it.isNotBlank() }?.let(Uri::parse)
        )
    }
}
