package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PcVoiceSample(val id: String, val name: String, val createdAt: String, val sizeBytes: Long)
data class PcVoiceListing(val epoch: String, val samples: List<PcVoiceSample>)

/** One PC per operation; never retry synthesis POSTs after uncertain network failures. */
class ChatTtsClient(private val context: Context) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 1_600_000
        const val CONTROL_TIMEOUT_MS = 60_000
        const val MAX_AUDIO_BYTES = 64 * 1024 * 1024
        const val MAX_ERROR_BYTES = 16 * 1024
        const val MAX_JSON_BYTES = 4 * 1024 * 1024
        val TAG_ID = Regex("[0-9a-f]{32}")
        val SAMPLE_ID = Regex("[0-9a-f]{64}")
    }

    private val base: String by lazy {
        GenerationAgentClient.candidateBases(context).firstOrNull()
            ?: error("設定でPC生成エージェントのURLを指定してください。")
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

    private fun voicePath(tagId: String): String {
        require(TAG_ID.matches(tagId)) { "タグ音声IDが不正です。" }
        return "/api/v1/tts/voices/$tagId"
    }

    fun listVoices(tagId: String): PcVoiceListing {
        val result = jsonRequest("GET", voicePath(tagId))
        val array = result.getJSONArray("samples")
        return PcVoiceListing(result.getString("epoch"), (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PcVoiceSample(item.getString("sample_id"), item.getString("name"),
                item.getString("created_at"), item.getLong("size_bytes"))
        })
    }

    fun deleteVoices(tagId: String, epoch: String, sampleId: String?): Int {
        require(TAG_ID.matches(epoch)) { "保存状態のIDが不正です。" }
        if (sampleId != null) { require(SAMPLE_ID.matches(sampleId)) { "音声IDが不正です。" } }
        val path = voicePath(tagId) + "?epoch=$epoch" + (sampleId?.let { "&sample_id=$it" } ?: "")
        return jsonRequest("DELETE", path).getInt("deleted")
    }

    private fun ensureVoice(binding: ChatVoice) {
        val voice = binding.voice
        require(SAMPLE_ID.matches(voice.sampleId)) { "音声IDが不正です。" }
        val listing = listVoices(binding.tagId)
        if (listing.samples.any { it.id == voice.sampleId }) { return }
        // Read/send bytes only on a confirmed cache miss on this PC.
        val audio = TagVoiceStore.readSample(context, voice)
        jsonRequest("POST", voicePath(binding.tagId), JSONObject().apply {
            put("epoch", listing.epoch)
            put("sample_id", voice.sampleId)
            put("tag_name", binding.tag)
            put("name", voice.name)
            put("audio_base64", Base64.encodeToString(audio, Base64.NO_WRAP))
        })
    }

    fun generate(text: String, binding: ChatVoice, destination: File) {
        ensureVoice(binding)
        val body = JSONObject().apply {
            put("text", text)
            put("voices", JSONArray().put(JSONObject().apply {
                put("tag_id", binding.tagId)
                put("sample_id", binding.voice.sampleId)
                put("ref_text", binding.voice.refText)
            }))
        }
        val conn = open("POST", "/api/v1/tts", READ_TIMEOUT_MS)
        try {
            sendBody(conn, body)
            checkStatus(conn)
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

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json, audio/wav")
            val key = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString("generation_agent_api_key", "")?.trim().orEmpty()
            if (key.isNotBlank()) { setRequestProperty("Authorization", "Bearer $key") }
        }
        try {
            register(conn)
            return conn
        } catch (error: Exception) {
            conn.disconnect()
            throw error
        }
    }

    private fun sendBody(conn: HttpURLConnection, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(bytes) }
    }

    private fun jsonRequest(method: String, path: String, body: JSONObject? = null): JSONObject {
        val conn = open(method, path, CONTROL_TIMEOUT_MS)
        try {
            if (body != null) { sendBody(conn, body) }
            checkStatus(conn)
            return JSONObject(conn.inputStream.use { it.readBytesLimited(MAX_JSON_BYTES).toString(Charsets.UTF_8) })
        } finally {
            conn.disconnect()
        }
    }

    private fun checkStatus(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status == HttpURLConnection.HTTP_OK) { return }
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            error("認証エラー。PCのconfig.local.jsonのapi_keyを、アプリのPC生成エージェント接続設定へ登録してください。")
        }
        val raw = conn.errorStream?.use { it.readBytesLimited(MAX_ERROR_BYTES).toString(Charsets.UTF_8) }
        val detail = raw?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
        if (status == HttpURLConnection.HTTP_NOT_FOUND && (detail == "route not found" || detail.isNullOrBlank())) {
            error("PCエージェントが音声保存APIに未対応です。PC側を更新して再起動してください。")
        }
        error(detail?.takeIf { it.isNotBlank() } ?: "TTS通信エラー (HTTP $status)。PCエージェントを確認してください。")
    }
}
