package com.example.kennys_dokidoki_wallpaper

/**
 * バッチ生成中にビルダーを変えたら、まだ始まっていない枚だけ今の選択で作り直す。
 */
object LiveBatchPromptPolicy {
    fun fingerprint(snapshot: GeneratedImageTagBinding.Snapshot): String {
        val selected = snapshot.selected.joinToString(";") { (card, level) ->
            "${card.id}=$level"
        }
        val randomCats = snapshot.randomEnabledCategories.sorted().joinToString(",")
        val included = snapshot.randomizerIncludedIds.sorted().joinToString(",")
        val roster = snapshot.roster.joinToString(";") { card ->
            listOf(
                card.id,
                card.category,
                card.mainPrompt,
                card.negativePrompt,
                card.appliedTags.sorted().joinToString("|"),
                if (card.useIndividualRandomizer) "1" else "0",
                card.randomizerProbability.toString()
            ).joinToString("/")
        }
        return listOf(
            selected,
            randomCats,
            included,
            roster,
            snapshot.width.toString(),
            snapshot.height.toString(),
            snapshot.steps.toString(),
            snapshot.sampler
        ).joinToString("\n")
    }

    fun pendingCount(total: Int, completed: Int, failed: Int): Int =
        (total - completed - failed).coerceAtLeast(0)

    fun pendingStart(total: Int, pending: Int): Int =
        (total - pending.coerceAtLeast(0)).coerceAtLeast(0)

    fun splicePrepared(
        existing: List<GeneratedImageTagBinding.PreparedImage>,
        pendingStart: Int,
        replacement: List<GeneratedImageTagBinding.PreparedImage>
    ): List<GeneratedImageTagBinding.PreparedImage> {
        val start = pendingStart.coerceAtLeast(0)
        val head = existing.take(start)
        val tail = existing.drop(start + replacement.size)
        return head + replacement + tail
    }

    fun shouldSendRefresh(previousFingerprint: String?, currentFingerprint: String, pending: Int): Boolean =
        pending > 0 && currentFingerprint.isNotBlank() && currentFingerprint != previousFingerprint
}
