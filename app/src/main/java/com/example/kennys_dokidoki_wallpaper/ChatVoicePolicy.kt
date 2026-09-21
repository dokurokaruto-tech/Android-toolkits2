package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject

data class TagVoice(val sampleId: String, val name: String, val refText: String = "") {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sampleId", sampleId)
        put("name", name)
        put("refText", refText)
    }

    companion object {
        fun fromJson(value: JSONObject): TagVoice = TagVoice(
            value.getString("sampleId"), value.getString("name"), value.optString("refText", "")
        )
    }
}

data class ChatVoice(val tag: String, val voice: TagVoice)

object ChatVoicePolicy {
    const val MAX_SAMPLE_BYTES = 6 * 1024 * 1024
    const val MAX_TEXT_LENGTH = 2000

    fun requireVoice(voices: List<ChatVoice>?): TagVoice {
        requireNotNull(voices) { "この返信には生成時の音声情報がありません。タグに音声を設定して返信を再生成してください。" }
        require(voices.isNotEmpty()) { "生成時に音声付きタグがありません。タグに音声を設定して返信を再生成してください。" }
        require(voices.size == 1) {
            "生成時の音声付きタグが複数あります（${voices.joinToString { it.tag }}）。1つにして返信を再生成してください。"
        }
        return voices.single().voice
    }

    fun speechText(raw: String): String {
        val text = ChatSuggestionParser.visibleText(raw)
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[img].*?\\[/img]", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
        require(text.isNotBlank()) { "読み上げる本文がありません。" }
        require(text.length <= MAX_TEXT_LENGTH) { "読み上げ本文は${MAX_TEXT_LENGTH}文字以下にしてください。" }
        return text
    }

    fun encode(voices: List<ChatVoice>): JSONArray = JSONArray().apply {
        voices.forEach { item ->
            put(JSONObject().apply {
                put("tag", item.tag)
                put("voice", item.voice.toJson())
            })
        }
    }

    fun decode(array: JSONArray): List<ChatVoice> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ChatVoice(item.getString("tag"), TagVoice.fromJson(item.getJSONObject("voice")))
    }
}
