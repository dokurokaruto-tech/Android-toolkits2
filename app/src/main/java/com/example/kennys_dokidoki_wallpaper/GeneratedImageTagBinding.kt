package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * 生成開始時点のカード選択と「自動付与するタグ」を凍結し、完成画像へ載せる。
 */
object GeneratedImageTagBinding {
    data class FrozenCard(
        val id: String,
        val category: String,
        val mainPrompt: String,
        val negativePrompt: String,
        val appliedTags: Set<String>,
        val useIndividualRandomizer: Boolean,
        val randomizerProbability: Int
    )

    data class Snapshot(
        val selected: List<Pair<FrozenCard, Int>>,
        val roster: List<FrozenCard>,
        val randomEnabledCategories: Set<String>,
        val randomizerIncludedIds: Set<String>,
        val width: Int,
        val height: Int,
        val steps: Int,
        val sampler: String,
        val batchCount: Int
    )

    data class PreparedImage(
        val prompt: String,
        val negativePrompt: String,
        val tags: List<String>,
        val cardStates: Map<String, Int> = emptyMap(),
        val randomPickedIds: Set<String> = emptySet(),
        val randomEnabledCategories: Set<String> = emptySet(),
        val width: Int = 0,
        val height: Int = 0,
        val steps: Int = 0,
        val sampler: String = ""
    )

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

    fun freezeCard(card: PromptCard): FrozenCard = FrozenCard(
        id = card.id,
        category = card.category,
        mainPrompt = card.mainPrompt,
        negativePrompt = card.negativePrompt,
        appliedTags = card.appliedTags.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        useIndividualRandomizer = card.useIndividualRandomizer,
        randomizerProbability = card.randomizerProbability
    )

    fun snapshotAtStart(
        selected: List<Pair<PromptCard, Int>>,
        roster: List<PromptCard>,
        randomEnabledCategories: Set<String>,
        randomizerIncludedIds: Set<String>,
        width: Int,
        height: Int,
        steps: Int,
        sampler: String,
        batchCount: Int
    ): Snapshot = Snapshot(
        selected = selected.map { freezeCard(it.first) to it.second },
        roster = roster.map(::freezeCard),
        randomEnabledCategories = randomEnabledCategories.toSet(),
        randomizerIncludedIds = randomizerIncludedIds.toSet(),
        width = width,
        height = height,
        steps = steps,
        sampler = sampler,
        batchCount = batchCount
    )

    fun buildPreparedImages(
        snapshot: Snapshot,
        chance: () -> Int,
        pickIndex: (Int) -> Int
    ): List<PreparedImage> {
        val prepared = mutableListOf<PreparedImage>()
        repeat(snapshot.batchCount.coerceAtLeast(1)) {
            val chosen = snapshot.selected.toMutableList()
            val randomPicked = linkedSetOf<String>()
            snapshot.roster.forEach { card ->
                if (card.useIndividualRandomizer && chosen.none { it.first.id == card.id }) {
                    if (chance() < card.randomizerProbability) {
                        chosen.add(card to 1)
                        randomPicked.add(card.id)
                    }
                }
            }
            snapshot.randomEnabledCategories.forEach { category ->
                val pool = categoryPool(snapshot, category, chosen)
                if (pool.isNotEmpty()) {
                    val card = pool[pickIndex(pool.size).coerceIn(0, pool.lastIndex)]
                    if (chosen.none { it.first.id == card.id }) {
                        chosen.add(card to 1)
                        randomPicked.add(card.id)
                    }
                }
            }
            if (chosen.isEmpty()) return@repeat
            val prompt = chosen.joinToString(", ") { (card, level) ->
                when (level) {
                    2 -> "(${card.mainPrompt}:1.2)"
                    3 -> "(${card.mainPrompt}:1.6)"
                    else -> card.mainPrompt
                }
            }.trim()
            val negative = chosen.map { it.first.negativePrompt }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")
                .trim()
            prepared.add(
                PreparedImage(
                    prompt = prompt,
                    negativePrompt = negative,
                    tags = tagsFromChosen(chosen),
                    cardStates = chosen.associate { it.first.id to it.second },
                    randomPickedIds = randomPicked,
                    randomEnabledCategories = snapshot.randomEnabledCategories,
                    width = snapshot.width,
                    height = snapshot.height,
                    steps = snapshot.steps,
                    sampler = snapshot.sampler
                )
            )
        }
        return prepared
    }

    /**
     * 個別ランダマイザーで外れたカードは、カテゴリ抽選の穴埋めにも使わない。
     * そうしないとプロンプトには出ていないのにタグだけ乗る。
     */
    fun categoryPool(
        snapshot: Snapshot,
        category: String,
        alreadyChosen: List<Pair<FrozenCard, Int>>
    ): List<FrozenCard> {
        val chosenIds = alreadyChosen.map { it.first.id }.toSet()
        val inCategory = snapshot.roster.filter { it.category.trim() == category.trim() }
        val included = inCategory.filter { snapshot.randomizerIncludedIds.contains(it.id) }
        val base = if (included.isNotEmpty()) included else inCategory
        return base.filter { card ->
            !card.useIndividualRandomizer || card.id in chosenIds
        }
    }

    fun tagsFromChosen(chosen: List<Pair<FrozenCard, Int>>): List<String> =
        collect(chosen.map { it.first.appliedTags }).toList()

    fun taskIndexFromUrl(url: String): Int? {
        val match = TASK_INDEX.find(url.substringBefore('?').substringBefore('#')) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun tagsForCompletedUrls(
        urls: List<String>,
        prepared: List<PreparedImage>
    ): List<Pair<String, List<String>>> {
        return urls.mapIndexed { order, url ->
            val fromName = taskIndexFromUrl(url)?.let { index ->
                prepared.getOrNull(index - 1)?.tags
            }
            url to (fromName ?: prepared.getOrNull(order)?.tags.orEmpty())
        }
    }

    fun preparedFromTagLists(tagLists: List<List<String>>): List<PreparedImage> =
        tagLists.map { PreparedImage("", "", it) }

    fun cardStatesForCompletedUrls(
        urls: List<String>,
        prepared: List<PreparedImage>
    ): List<Pair<String, Map<String, Int>>> {
        return urls.mapIndexed { order, url ->
            val fromName = taskIndexFromUrl(url)?.let { index ->
                prepared.getOrNull(index - 1)?.cardStates
            }
            url to (fromName ?: prepared.getOrNull(order)?.cardStates.orEmpty())
        }
    }

    fun encodeCardStateLists(lists: List<Map<String, Int>>): String {
        val array = JSONArray()
        lists.forEach { states ->
            array.put(JSONObject().also { item ->
                states.forEach { (id, level) ->
                    if (id.isNotBlank()) item.put(id, level)
                }
            })
        }
        return array.toString()
    }

    fun decodeCardStateLists(raw: String?): List<Map<String, Int>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(parseCardStates(array.optJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseStringSet(raw: JSONArray?): Set<String> {
        if (raw == null) return emptySet()
        val result = linkedSetOf<String>()
        for (index in 0 until raw.length()) {
            val value = raw.optString(index).trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }

    fun encodeStringSet(values: Collection<String>): JSONArray =
        JSONArray().also { array -> values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { array.put(it) } }

    fun encodeRandomMetaLists(
        picked: List<Set<String>>,
        categories: List<Set<String>>
    ): String {
        val array = JSONArray()
        val count = maxOf(picked.size, categories.size)
        for (index in 0 until count) {
            array.put(JSONObject().apply {
                put("picked", encodeStringSet(picked.getOrNull(index).orEmpty()))
                put("categories", encodeStringSet(categories.getOrNull(index).orEmpty()))
            })
        }
        return array.toString()
    }

    fun decodeRandomMetaLists(raw: String?): List<Pair<Set<String>, Set<String>>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: JSONObject()
                    add(parseStringSet(item.optJSONArray("picked")) to parseStringSet(item.optJSONArray("categories")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun parseCardStates(raw: org.json.JSONObject?): Map<String, Int> {
        if (raw == null) return emptyMap()
        val result = linkedMapOf<String, Int>()
        raw.keys().forEach { id ->
            val key = id.trim()
            val level = raw.optInt(id, 0)
            if (key.isNotEmpty() && level in 1..3) result[key] = level
        }
        return result
    }

    fun encodeTagLists(lists: List<Collection<String>>): String {
        val array = JSONArray()
        lists.forEach { tags ->
            array.put(JSONArray().also { item ->
                collect(listOf(tags)).forEach { item.put(it) }
            })
        }
        return array.toString()
    }

    fun decodeTagLists(raw: String?): List<List<String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONArray(index) ?: JSONArray()
                    add(buildList {
                        for (tagIndex in 0 until item.length()) {
                            val tag = item.optString(tagIndex).trim()
                            if (tag.isNotEmpty()) add(tag)
                        }
                    })
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeTagList(tags: Collection<String>): String =
        JSONArray().also { array -> collect(listOf(tags)).forEach { array.put(it) } }.toString()

    fun parseTagList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val tag = array.optString(index).trim()
                    if (tag.isNotEmpty()) add(tag)
                }
            }
        }.getOrDefault(emptyList())
    }

    private val TASK_INDEX = Regex("""_(\d{4})\.(?:png|jpg|jpeg|webp)$""", RegexOption.IGNORE_CASE)
}
