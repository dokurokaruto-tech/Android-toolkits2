package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads an agent-hosted image before registering it in the local wallpaper library. */
object GeneratedImageImporter {
    suspend fun download(context: Context, remoteUri: Uri, date: String): Uri? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val rootUri = prefs.getString("gen_save_folder_uri", null)?.let(Uri::parse) ?: return@withContext null
        val root = DocumentFile.fromTreeUri(context, rootUri)?.takeIf { it.exists() && it.isDirectory }
            ?: return@withContext null
        val safeDate = date.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: "downloaded"
        val dateFolder = root.findFile(safeDate)?.takeIf { it.isDirectory } ?: root.createDirectory(safeDate)
            ?: return@withContext null
        val originalName = remoteUri.lastPathSegment?.substringAfterLast('/')
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: "GEN_${System.currentTimeMillis()}.png"

        // Re-importing the same PC image reuses the device file rather than creating "(1)" copies.
        dateFolder.findFile(originalName)?.takeIf { it.isFile }?.let { return@withContext it.uri }
        val extension = originalName.substringAfterLast('.', "png").lowercase()
        val mime = when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
        val file = dateFolder.createFile(mime, originalName) ?: return@withContext null
        try {
            val connection = (URL(remoteUri.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 120000
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IOException("PC image download failed: HTTP ${connection.responseCode}")
                }
                val output = context.contentResolver.openOutputStream(file.uri)
                    ?: throw IOException("device save folder is not writable")
                output.use { destination -> connection.inputStream.use { source -> source.copyTo(destination) } }
                file.uri
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }
}
