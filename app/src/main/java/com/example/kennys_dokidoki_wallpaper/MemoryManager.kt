package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray

object MemoryManager {
    private const val PREFS_NAME = "memory_prefs"
    private const val KEY_MEMORIES = "memories_list"

    val memories = mutableListOf<String>()

    fun loadMemories(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEMORIES, null)
        memories.clear()
        if (json != null) {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                memories.add(array.getString(i))
            }
        }
    }

    fun saveMemories(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray(memories)
        prefs.edit().putString(KEY_MEMORIES, array.toString()).apply()
    }

    fun addMemory(context: Context, memory: String) {
        if (memory.isNotBlank() && !memories.contains(memory)) {
            memories.add(memory)
            saveMemories(context)
        }
    }

    fun editMemory(context: Context, index: Int, newMemory: String) {
        if (index in 0 until memories.size && newMemory.isNotBlank()) {
            memories[index] = newMemory
            saveMemories(context)
        }
    }

    fun removeMemory(context: Context, index: Int) {
        if (index in 0 until memories.size) {
            memories.removeAt(index)
            saveMemories(context)
        }
    }
}
