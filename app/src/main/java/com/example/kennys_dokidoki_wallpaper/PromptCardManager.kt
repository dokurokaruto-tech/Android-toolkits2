package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object PromptCardManager {
    private const val PREFS_NAME = "prompt_card_prefs"
    private const val KEY_CARDS = "prompt_cards"
    private const val KEY_CATEGORIES = "prompt_categories"
    private const val KEY_RANDOM_CATEGORIES = "prompt_random_categories"
    private const val KEY_SELECTION_LEVELS = "prompt_selection_levels"
    private const val KEY_COLLAPSED_CATEGORIES = "prompt_collapsed_categories"
    private const val KEY_RANDOMIZER_INCLUDED_IDS = "prompt_randomizer_included_ids"
    
    val promptCards = mutableListOf<PromptCard>()
    val categoryOrder = mutableListOf<String>()
    val randomEnabledCategories = mutableSetOf<String>()
    val selectionLevels = mutableMapOf<String, Int>()
    val collapsedCategories = mutableSetOf<String>()
    val randomizerIncludedIds = mutableSetOf<String>()

    fun loadCards(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load Cards
        val cardsJson = prefs.getString(KEY_CARDS, null)
        promptCards.clear()
        if (cardsJson != null) {
            val array = JSONArray(cardsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val card = PromptCard(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    mainPrompt = obj.getString("mainPrompt"),
                    negativePrompt = obj.getString("negativePrompt"),
                    thumbnailUri = obj.optString("thumbnailUri").let { if (it.isNotEmpty()) Uri.parse(it) else null },
                    category = obj.optString("category", "未分類"),
                    useIndividualRandomizer = obj.optBoolean("useIndividualRandomizer", false),
                    randomizerProbability = obj.optInt("randomizerProbability", 50)
                )
                val tagsArray = obj.optJSONArray("appliedTags")
                if (tagsArray != null) {
                    for (j in 0 until tagsArray.length()) {
                        card.appliedTags.add(tagsArray.getString(j))
                    }
                }
                promptCards.add(card)
            }
        }

        // Load Category Order
        val catsJson = prefs.getString(KEY_CATEGORIES, null)
        categoryOrder.clear()
        if (catsJson != null) {
            val array = JSONArray(catsJson)
            for (i in 0 until array.length()) {
                categoryOrder.add(array.getString(i))
            }
        }
        
        // Load Random Enabled Categories
        val randomCatsJson = prefs.getString(KEY_RANDOM_CATEGORIES, null)
        randomEnabledCategories.clear()
        if (randomCatsJson != null) {
            val array = JSONArray(randomCatsJson)
            for (i in 0 until array.length()) {
                randomEnabledCategories.add(array.getString(i))
            }
        }

        // Load Selection Levels
        val levelsJson = prefs.getString(KEY_SELECTION_LEVELS, null)
        selectionLevels.clear()
        if (levelsJson != null) {
            val obj = JSONObject(levelsJson)
            obj.keys().forEach { id ->
                selectionLevels[id] = obj.getInt(id)
            }
        }

        // Load Collapsed Categories
        val collapsedJson = prefs.getString(KEY_COLLAPSED_CATEGORIES, null)
        collapsedCategories.clear()
        if (collapsedJson != null) {
            val array = JSONArray(collapsedJson)
            for (i in 0 until array.length()) {
                collapsedCategories.add(array.getString(i))
            }
        }

        // Load Randomizer Included IDs
        val randomizerIncludedJson = prefs.getString(KEY_RANDOMIZER_INCLUDED_IDS, null)
        randomizerIncludedIds.clear()
        if (randomizerIncludedJson != null) {
            val array = JSONArray(randomizerIncludedJson)
            for (i in 0 until array.length()) {
                randomizerIncludedIds.add(array.getString(i))
            }
        }
        
        // Ensure all categories in cards are in the order list
        promptCards.forEach { card ->
            if (!categoryOrder.contains(card.category)) {
                categoryOrder.add(card.category)
            }
        }
        if (categoryOrder.isEmpty()) categoryOrder.add("未分類")
    }

    fun saveCards(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Save Cards
        val cardsArray = JSONArray()
        promptCards.forEach { card ->
            val obj = JSONObject().apply {
                put("id", card.id)
                put("label", card.label)
                put("mainPrompt", card.mainPrompt)
                put("negativePrompt", card.negativePrompt)
                put("thumbnailUri", card.thumbnailUri?.toString() ?: "")
                put("category", card.category)
                put("useIndividualRandomizer", card.useIndividualRandomizer)
                put("randomizerProbability", card.randomizerProbability)
                val tagsArray = JSONArray()
                card.appliedTags.forEach { tagsArray.put(it) }
                put("appliedTags", tagsArray)
            }
            cardsArray.put(obj)
        }
        
        // Save Categories
        val catsArray = JSONArray()
        categoryOrder.forEach { catsArray.put(it) }
        
        // Save Random Categories
        val randomCatsArray = JSONArray()
        randomEnabledCategories.forEach { randomCatsArray.put(it) }

        // Save Selection Levels
        val levelsObj = JSONObject()
        selectionLevels.forEach { (id, level) ->
            levelsObj.put(id, level)
        }

        // Save Collapsed Categories
        val collapsedArray = JSONArray()
        collapsedCategories.forEach { collapsedArray.put(it) }

        // Save Randomizer Included IDs
        val randomizerIncludedArray = JSONArray()
        randomizerIncludedIds.forEach { randomizerIncludedArray.put(it) }
        
        prefs.edit()
            .putString(KEY_CARDS, cardsArray.toString())
            .putString(KEY_CATEGORIES, catsArray.toString())
            .putString(KEY_RANDOM_CATEGORIES, randomCatsArray.toString())
            .putString(KEY_SELECTION_LEVELS, levelsObj.toString())
            .putString(KEY_COLLAPSED_CATEGORIES, collapsedArray.toString())
            .putString(KEY_RANDOMIZER_INCLUDED_IDS, randomizerIncludedArray.toString())
            .apply()
    }

    fun addCard(context: Context, card: PromptCard) {
        promptCards.add(card)
        if (!categoryOrder.contains(card.category)) {
            categoryOrder.add(card.category)
        }
        saveCards(context)
    }

    fun deleteCard(context: Context, card: PromptCard) {
        promptCards.remove(card)
        selectionLevels.remove(card.id)
        randomizerIncludedIds.remove(card.id)
        saveCards(context)
    }

    fun renameCategory(context: Context, oldName: String, newName: String) {
        val index = categoryOrder.indexOf(oldName)
        if (index != -1) {
            categoryOrder[index] = newName
        }
        promptCards.forEach {
            if (it.category == oldName) it.category = newName
        }
        if (randomEnabledCategories.contains(oldName)) {
            randomEnabledCategories.remove(oldName)
            randomEnabledCategories.add(newName)
        }
        if (collapsedCategories.contains(oldName)) {
            collapsedCategories.remove(oldName)
            collapsedCategories.add(newName)
        }
        saveCards(context)
    }

    fun deleteCategory(context: Context, category: String) {
        categoryOrder.remove(category)
        val removedCards = promptCards.filter { it.category == category }
        promptCards.removeAll { it.category == category }
        randomEnabledCategories.remove(category)
        collapsedCategories.remove(category)
        removedCards.forEach { randomizerIncludedIds.remove(it.id) }
        saveCards(context)
    }
    
    fun addCategory(context: Context, category: String) {
        if (!categoryOrder.contains(category)) {
            categoryOrder.add(category)
            saveCards(context)
        }
    }
    
    fun toggleRandom(context: Context, category: String) {
        if (randomEnabledCategories.contains(category)) {
            randomEnabledCategories.remove(category)
        } else {
            randomEnabledCategories.add(category)
        }
        saveCards(context)
    }

    fun toggleCollapsed(context: Context, category: String) {
        if (collapsedCategories.contains(category)) {
            collapsedCategories.remove(category)
        } else {
            collapsedCategories.add(category)
        }
        saveCards(context)
    }

    fun toggleRandomizerInclusion(context: Context, cardId: String) {
        if (randomizerIncludedIds.contains(cardId)) {
            randomizerIncludedIds.remove(cardId)
        } else {
            randomizerIncludedIds.add(cardId)
        }
        saveCards(context)
    }
}
