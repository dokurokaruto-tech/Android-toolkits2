package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject

/**
 * タグ文章のAI置き換えを、一気に貼らずストリーミングで見せるための規則。
 */
object TagPromptStreamPolicy {
    sealed class SseEvent {
        data class Data(val payload: String) : SseEvent()
        object Done : SseEvent()
    }

    fun userMessage(
        tagName: String,
        instruction: String,
        existingPrompt: String,
        useTagName: Boolean,
        useExisting: Boolean
    ): String {
        val target = if (useTagName) tagName.trim() else ""
        val draft = if (useExisting) existingPrompt else ""
        return buildString {
            append("ターゲット: ")
            append(target)
            append("\n指示: ")
            append(instruction)
            if (useExisting) {
                append("\n既存設定: ")
                append(draft)
            }
        }
    }

    fun parseSseLine(line: String): SseEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return SseEvent.Done
        return SseEvent.Data(payload)
    }

    fun contentFromChunk(payload: String): String {
        if (payload.isBlank()) return ""
        return runCatching {
            val delta = JSONObject(payload)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?: return ""
            if (!delta.has("content") || delta.isNull("content")) return ""
            delta.optString("content")
        }.getOrDefault("")
    }

    fun applyFailedInstruction(existing: String, instruction: String): String {
        val extra = instruction.trim()
        if (extra.isEmpty()) return existing
        val current = existing.trimEnd()
        if (current.isEmpty()) return extra
        return current + "\n\n" + extra
    }
}
