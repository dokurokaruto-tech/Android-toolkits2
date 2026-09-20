package com.example.kennys_dokidoki_wallpaper

internal object ModelSearch {
    fun matches(name: String, id: String, query: String): Boolean {
        val term = query.trim()
        return name.contains(term, ignoreCase = true) || id.contains(term, ignoreCase = true)
    }
}
