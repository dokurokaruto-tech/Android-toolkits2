package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object PresetManager {
    private const val PREFS_NAME = "preset_prefs"
    private const val KEY_PRESETS = "presets"
    private const val KEY_CATEGORY_ORDER = "preset_category_order"
    private const val KEY_COLLAPSED_CATEGORIES = "preset_collapsed_categories"

    val presets = mutableListOf<Preset>()
    val categoryOrder = mutableListOf<String>()
    val collapsedCategories = mutableSetOf<String>()

    fun loadPresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val json = prefs.getString(KEY_PRESETS, null)
        presets.clear()
        if (json != null) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val promptStatesMap = mutableMapOf<String, Int>()
                    val statesJson = obj.getJSONObject("activePromptStates")
                    statesJson.keys().forEach { id ->
                        promptStatesMap[id] = statesJson.getInt(id)
                    }

                    val randomCats = mutableSetOf<String>()
                    val randomCatsArray = obj.getJSONArray("randomEnabledCategories")
                    for (j in 0 until randomCatsArray.length()) {
                        randomCats.add(randomCatsArray.getString(j))
                    }

                    presets.add(Preset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        category = obj.getString("category"),
                        activePromptStates = promptStatesMap,
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        steps = obj.getInt("steps"),
                        batchCount = obj.getInt("batchCount"),
                        sampler = obj.getString("sampler"),
                        randomEnabledCategories = randomCats,
                        thumbnailUri = obj.optString("thumbnailUri", "").let { if (it.isNotEmpty()) Uri.parse(it) else null }
                    ))
                }
            } catch (e: Exception) {
                Log.e("PresetManager", "Failed to load presets", e)
            }
        }

        val catsJson = prefs.getString(KEY_CATEGORY_ORDER, null)
        categoryOrder.clear()
        if (catsJson != null) {
            try {
                val array = JSONArray(catsJson)
                for (i in 0 until array.length()) {
                    categoryOrder.add(array.getString(i))
                }
            } catch (e: Exception) { }
        }

        val collapsedJson = prefs.getString(KEY_COLLAPSED_CATEGORIES, null)
        collapsedCategories.clear()
        if (collapsedJson != null) {
            try {
                val array = JSONArray(collapsedJson)
                for (i in 0 until array.length()) {
                    collapsedCategories.add(array.getString(i))
                }
            } catch (e: Exception) { }
        }
        
        presets.forEach { 
            if (!categoryOrder.contains(it.category)) categoryOrder.add(it.category)
        }
        if (categoryOrder.isEmpty()) categoryOrder.add("未分類")
    }

    fun savePresets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        presets.forEach { preset ->
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("category", preset.category)
                val statesObj = JSONObject()
                preset.activePromptStates.forEach { (id, level) -> statesObj.put(id, level) }
                put("activePromptStates", statesObj)
                put("width", preset.width)
                put("height", preset.height)
                put("steps", preset.steps)
                put("batchCount", preset.batchCount)
                put("sampler", preset.sampler)
                val randomArray = JSONArray()
                preset.randomEnabledCategories.forEach { randomArray.put(it) }
                put("randomEnabledCategories", randomArray)
                put("thumbnailUri", preset.thumbnailUri?.toString() ?: "")
            }
            array.put(obj)
        }
        
        val catsArray = JSONArray()
        categoryOrder.forEach { catsArray.put(it) }

        val collapsedArray = JSONArray()
        collapsedCategories.forEach { collapsedArray.put(it) }

        prefs.edit()
            .putString(KEY_PRESETS, array.toString())
            .putString(KEY_CATEGORY_ORDER, catsArray.toString())
            .putString(KEY_COLLAPSED_CATEGORIES, collapsedArray.toString())
            .apply()
    }

    fun addPreset(context: Context, preset: Preset) {
        presets.add(preset)
        if (!categoryOrder.contains(preset.category)) categoryOrder.add(preset.category)
        savePresets(context)
    }

    fun deletePreset(context: Context, preset: Preset) {
        presets.remove(preset)
        savePresets(context)
    }

    fun renameCategory(context: Context, oldName: String, newName: String) {
        val index = categoryOrder.indexOf(oldName)
        if (index != -1) {
            categoryOrder[index] = newName
        }
        presets.forEach {
            if (it.category == oldName) it.category = newName
        }
        if (collapsedCategories.contains(oldName)) {
            collapsedCategories.remove(oldName)
            collapsedCategories.add(newName)
        }
        savePresets(context)
    }

    fun deleteCategory(context: Context, category: String) {
        categoryOrder.remove(category)
        presets.removeAll { it.category == category }
        collapsedCategories.remove(category)
        savePresets(context)
    }

    fun addCategory(context: Context, category: String) {
        if (!categoryOrder.contains(category)) {
            categoryOrder.add(category)
            savePresets(context)
        }
    }

    fun toggleCollapsed(context: Context, category: String) {
        if (collapsedCategories.contains(category)) {
            collapsedCategories.remove(category)
        } else {
            collapsedCategories.add(category)
        }
        savePresets(context)
    }
}
