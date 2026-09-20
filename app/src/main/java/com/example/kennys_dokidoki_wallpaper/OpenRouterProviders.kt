package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object OpenRouterProviders {
    suspend fun load(context: Context, modelId: String): List<OpenRouterEndpoint> =
        withContext(Dispatchers.IO) {
            val key = OpenRouterManager.getActiveApiKey(context)
            OpenRouterEndpoints.parse(EndpointClient.fetch(modelId, key))
        }
}

private object EndpointClient {
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val TIMEOUT_MS = 15_000

    fun fetch(modelId: String, apiKey: String?): String {
        // 各パス要素を符号化し、:free などのモデル識別子も保持する。
        val path = modelId.split('/').joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val connection = URL("$MODELS_URL/$path/endpoints").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (!apiKey.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("OpenRouter endpoints HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
