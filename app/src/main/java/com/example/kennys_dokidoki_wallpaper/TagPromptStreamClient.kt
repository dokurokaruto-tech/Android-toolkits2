package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI互換のチャットAPIをSSEで読む。
 */
object TagPromptStreamClient {
    data class Endpoint(
        val url: String,
        val apiKey: String,
        val model: String,
        val extraHeaders: Map<String, String> = emptyMap()
    )

    class HttpException(val code: Int, message: String) : Exception(message)

    fun stream(
        endpoint: Endpoint,
        systemPrompt: String,
        userText: String,
        imageDataUrl: String?,
        isActive: () -> Boolean = { true },
        onDelta: (String) -> Unit = {}
    ): String {
        val conn = (URL(endpoint.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            endpoint.extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(requestBody(endpoint.model, systemPrompt, userText, imageDataUrl))
        }
        val code = conn.responseCode
        if (code != 200) {
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            throw HttpException(code, error.ifBlank { "HTTP $code" })
        }
        val accumulated = StringBuilder()
        try {
            conn.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!isActive()) break
                    when (val event = TagPromptStreamPolicy.parseSseLine(line)) {
                        TagPromptStreamPolicy.SseEvent.Done -> break
                        is TagPromptStreamPolicy.SseEvent.Data -> {
                            val piece = TagPromptStreamPolicy.contentFromChunk(event.payload)
                            if (piece.isNotEmpty()) {
                                accumulated.append(piece)
                                onDelta(accumulated.toString())
                            }
                        }
                        null -> Unit
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return accumulated.toString()
    }

    internal fun requestBody(
        model: String,
        systemPrompt: String,
        userText: String,
        imageDataUrl: String?
    ): String {
        val userContent = if (imageDataUrl.isNullOrBlank()) {
            userText
        } else {
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", userText))
                put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", imageDataUrl)
                    )
                )
            }
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .toString()
    }
}
