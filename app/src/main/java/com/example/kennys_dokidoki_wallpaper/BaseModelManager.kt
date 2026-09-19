package com.example.kennys_dokidoki_wallpaper

// Base (checkpoint) model behind the whole builder. Single selection only,
// stored on the PC as the global SD model. Presets never capture it.
object BaseModelManager {
    private const val THUMB_PREFS = "base_model_thumbs"

    val models = mutableListOf<CheckpointInfo>()
    var activeName: String? = null
        private set
    private val thumbs = mutableMapOf<String, String>()

    suspend fun load(context: android.content.Context) {
        val (list, active) = GenerationAgentClient.fetchCheckpoints(context)
        models.clear()
        models.addAll(list)
        activeName = active
        loadThumbs(context)
    }

    fun loadThumbs(context: android.content.Context) {
        val prefs = context.getSharedPreferences(THUMB_PREFS, android.content.Context.MODE_PRIVATE)
        thumbs.clear()
        prefs.all.forEach { (name, value) ->
            (value as? String)?.takeIf { it.isNotBlank() }?.let { thumbs[name] = it }
        }
    }

    fun thumbnailFor(name: String): String? = thumbs[name]

    fun setThumbnail(context: android.content.Context, name: String, uri: String?) {
        val prefs = context.getSharedPreferences(THUMB_PREFS, android.content.Context.MODE_PRIVATE)
        if (uri.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            thumbs.remove(name)
        } else {
            prefs.edit().putString(name, uri).apply()
            thumbs[name] = uri
        }
    }

    suspend fun select(context: android.content.Context, name: String): String? {
        val switched = GenerationAgentClient.setActiveCheckpoint(context, name)
        if (switched != null) {
            activeName = switched
        }
        return switched
    }

    fun displayName(fileName: String): String {
        return fileName.substringBeforeLast(".").ifBlank { fileName }
    }
}
