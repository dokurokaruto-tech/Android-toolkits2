package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** サムネイルキャッシュではなく、保存先にある原画像を確認する。 */
object GeneratedSavedImages {
    private const val PREFS = "settings"
    private const val SAVE_FOLDER = "gen_save_folder_uri"

    suspend fun contains(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ref = GeneratedImageIdentity.remoteRef(uri.toString())
        if (ref != null) {
            return@withContext GeneratedImageIdentity.key(ref) in keys(context, ref.date)
        }
        try {
            val file = when (uri.scheme) {
                "content" -> DocumentFile.fromSingleUri(context, uri)
                "file" -> uri.path?.let { DocumentFile.fromFile(java.io.File(it)) }
                else -> null
            }
            file != null && file.isFile && file.canRead() && file.length() > 0L
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    suspend fun keys(context: Context, date: String): Set<String> = withContext(Dispatchers.IO) {
        val rootUri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SAVE_FOLDER, null) ?: return@withContext emptySet()
        try {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
                ?: return@withContext emptySet()
            val folder = root.findFile(date)?.takeIf { it.isDirectory }
                ?: return@withContext emptySet()
            folder.listFiles().mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (!file.isFile || file.length() <= 0L) {
                    return@mapNotNull null
                }
                GeneratedImageIdentity.key(GeneratedImageIdentity.RemoteRef(date, name))
            }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: IllegalArgumentException) {
            emptySet()
        }
    }
}
