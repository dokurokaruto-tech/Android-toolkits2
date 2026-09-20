package com.example.kennys_dokidoki_wallpaper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** OpenRouterの判断APIと生成APIへの通信だけを担当する。 */
internal object JevElementClient {
    const val CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val MAX_RESPONSE_CHARS = 500_000

    suspend fun post(endpoint: String, key: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(JevElementPolicy.endpoint(endpoint)).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // リダイレクト先へBearerキーを送らない。
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("HTTP-Referer", "https://github.com/dokurokaruto-tech/Android-toolkits2")
            connection.setRequestProperty("X-Title", "Android Toolkits")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("OpenRouter HTTP $code。接続先・モデルID・APIキー・残高を確認してください。")
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val result = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) { break }
                    require(result.length + count <= MAX_RESPONSE_CHARS) { "API応答が大きすぎます。" }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
            JSONObject(raw)
        } finally {
            connection.disconnect()
        }
    }
}
