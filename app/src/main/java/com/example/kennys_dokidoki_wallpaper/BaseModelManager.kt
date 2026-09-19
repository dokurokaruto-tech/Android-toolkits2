package com.example.kennys_dokidoki_wallpaper

// Base (checkpoint) model behind the whole builder. Single selection only,
// stored on the PC as the global SD model. Presets never capture it.
object BaseModelManager {
    val models = mutableListOf<CheckpointInfo>()
    var activeName: String? = null
        private set

    suspend fun load(context: android.content.Context) {
        val (list, active) = GenerationAgentClient.fetchCheckpoints(context)
        models.clear()
        models.addAll(list)
        activeName = active
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
