package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject

data class PromptCardAiResult(val mainPrompt: String, val negativePrompt: String)

/** Parses strict JSON first, then a readable MAIN/NEGATIVE fallback for smaller local LLMs. */
object PromptCardAiResponseParser {
    fun parse(raw: String): PromptCardAiResult {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val jsonCandidate = cleaned.substringAfter('{', "").let {
            if (it.isEmpty()) "" else "{$it"
        }.substringBeforeLast('}', "").let {
            if (it.isEmpty()) "" else "$it}"
        }
        if (jsonCandidate.isNotEmpty()) {
            runCatching {
                val json = JSONObject(jsonCandidate)
                val main = json.optString("main_prompt").trim()
                val negative = json.optString("negative_prompt").trim()
                if (main.isNotEmpty()) return PromptCardAiResult(main, negative)
            }
        }

        var main = ""
        var negative = ""
        cleaned.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("MAIN:", true) -> main = trimmed.substringAfter(':').trim()
                trimmed.startsWith("NEGATIVE:", true) -> negative = trimmed.substringAfter(':').trim()
            }
        }
        if (main.isNotEmpty()) return PromptCardAiResult(main, negative)
        throw IllegalArgumentException("LLMの返答からプロンプトを読み取れませんでした")
    }
}
