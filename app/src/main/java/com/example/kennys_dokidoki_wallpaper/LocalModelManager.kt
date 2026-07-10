package com.example.kennys_dokidoki_wallpaper

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import java.io.File

data class LocalModel(
    val name: String,
    val file: File,
    val isDownloaded: Boolean = false,
    val downloadId: Long = -1L
)

object LocalModelManager {
    private const val MODELS_DIR = "models"
    private const val PREF_NAME = "local_model_prefs"
    private const val KEY_SELECTED_MODEL = "selected_model_name"
    
    fun getModelsDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAllModels(context: Context): List<LocalModel> {
        val dir = getModelsDir(context)
        val files = dir.listFiles() ?: emptyArray()
        
        // .gguf, .litertlm, .bin, .tflite, .model などを候補にするわ
        val modelExtensions = listOf("gguf", "litertlm", "bin", "tflite", "model")
        
        return files
            .filter { file ->
                val ext = file.extension.lowercase()
                modelExtensions.contains(ext) || file.length() > 100 * 1024 * 1024 // 100MB以上なら何でもOK！
            }
            .map { LocalModel(it.name, it, true) }
            .sortedByDescending { it.file.lastModified() }
    }

    fun downloadModel(context: Context, url: String, fileName: String): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("AI Model Download: $fileName")
            .setDescription("Downloading LLM model for local chat")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, null, "$MODELS_DIR/$fileName")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun deleteModel(model: LocalModel): Boolean {
        return if (model.file.exists()) {
            model.file.delete()
        } else false
    }

    /**
     * 設定で選択されたモデルを取得するわ。
     */
    fun getSelectedModel(context: Context): LocalModel? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val selectedName = prefs.getString(KEY_SELECTED_MODEL, null) ?: return null
        return getAllModels(context).find { it.name == selectedName }
    }

    /**
     * 使用するモデルを設定に保存するわ。
     */
    fun setSelectedModel(context: Context, modelName: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SELECTED_MODEL, modelName).apply()
    }

    /**
     * ダウンロード済みの最初のモデルを返すわ。
     */
    fun getFirstAvailableModel(context: Context): LocalModel? {
        return getAllModels(context).firstOrNull()
    }

    /**
     * 指定された名前のモデルが選択中かどうかを返すわ。
     */
    fun isSelected(context: Context, modelName: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val selectedName = prefs.getString(KEY_SELECTED_MODEL, null) ?: return false
        return selectedName == modelName
    }
}
