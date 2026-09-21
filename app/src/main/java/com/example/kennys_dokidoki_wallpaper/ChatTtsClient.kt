package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** One request; never retry a POST automatically after an uncertain network failure. */
class ChatTtsClient(private val context: Context) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 1_600_000
        const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
        const val MAX_ERROR_BYTES = 16 * 1024
    }

    private var connection: HttpURLConnection? = null
    private var canceled = false

    @Synchronized
    fun cancel() {
        canceled = true
        connection?.disconnect()
    }

    @Synchronized
    private fun register(conn: HttpURLConnection) {
        check(!canceled) { "TTS通信を中止しました。" }
        connection = conn
    }

    fun generate(text: String, voice: TagVoice, destination: File) {
        val audio = TagVoiceStore.readSample(context, voice)
        val base = GenerationAgentClient.candidateBases(context).firstOrNull()
            ?: error("設定でPC生成エージェントのURLを指定してください。")
        val body = JSONObject().apply {
            put("text", text)
            put("voices", JSONArray().put(JSONObject().apply {
                put("audio_base64", Base64.encodeToString(audio, Base64.NO_WRAP))
                put("ref_text", voice.refText)
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL("$base/api/v1/tts").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/wav")
            val key = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("generation_agent_api_key", "")?.trim().orEmpty()
            if (key.isNotBlank()) { setRequestProperty("Authorization", "Bearer $key") }
        }
        try {
            register(conn)
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val raw = conn.errorStream?.use { it.readBytesLimited(MAX_ERROR_BYTES).toString(Charsets.UTF_8) }
                val detail = raw?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                error(detail?.takeIf { it.isNotBlank() } ?: "TTS通信エラー (HTTP $status)。PCエージェントを更新・確認してください。")
            }
            check(conn.contentType?.substringBefore(';') == "audio/wav") { "PCから音声以外の応答が返りました。" }
            conn.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) { break }
                        total += count
                        check(total <= MAX_AUDIO_BYTES) { "生成音声が大きすぎます。" }
                        output.write(buffer, 0, count)
                    }
                    check(total > 44) { "生成音声が空です。" }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
