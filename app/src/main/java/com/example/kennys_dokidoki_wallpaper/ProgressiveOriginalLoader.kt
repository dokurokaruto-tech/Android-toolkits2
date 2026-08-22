package com.example.kennys_dokidoki_wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads independently decodable lossless strips in top-to-bottom network order. */
object ProgressiveOriginalLoader {
    suspend fun load(
        originalUri: Uri,
        onDimensions: (width: Int, height: Int) -> Unit,
        onTile: (bitmap: Bitmap, top: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val original = URL(originalUri.toString())
        val marker = "/api/v1/files/"
        if (!original.path.contains(marker)) {
            throw IOException("この画像サーバーは段階読み込みに対応していません")
        }
        val progressivePath = original.path.replaceFirst(marker, "/api/v1/progressive/")
        val manifestUrl = URL(
            original.protocol,
            original.host,
            original.port,
            "$progressivePath/manifest${original.query?.let { "?$it" } ?: ""}"
        )
        val manifest = JSONObject(readBytes(manifestUrl).toString(Charsets.UTF_8))
        val width = manifest.getInt("width")
        val height = manifest.getInt("height")
        withContext(Dispatchers.Main) { onDimensions(width, height) }

        val tileArray = manifest.getJSONArray("tiles")
        for (index in 0 until tileArray.length()) {
            coroutineContext.ensureActive()
            val tile = tileArray.getJSONObject(index)
            val relative = tile.getString("url")
            val tokenQuery = original.query?.let { "?$it" }.orEmpty()
            val tileUrl = URL(original.protocol, original.host, original.port, relative + tokenQuery)
            val bytes = readBytes(tileUrl)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IOException("画像タイル ${index + 1} をデコードできません")
            try {
                withContext(Dispatchers.Main) { onTile(bitmap, tile.getInt("top")) }
            } catch (error: Throwable) {
                if (!bitmap.isRecycled) bitmap.recycle()
                throw error
            }
        }
    }

    private fun readBytes(url: URL): ByteArray {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
            useCaches = false
            setRequestProperty("Cache-Control", "no-store")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Progressive image HTTP ${connection.responseCode}")
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
