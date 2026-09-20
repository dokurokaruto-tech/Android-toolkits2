package com.example.kennys_dokidoki_wallpaper

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object OpenRouterRouting {
    private const val MODEL_KEY = "chat_openrouter_model"
    private const val PROVIDER_MODEL_KEY = "chat_openrouter_provider_model"
    private const val PROVIDER_TAG_KEY = "chat_openrouter_provider_tag"

    fun save(prefs: SharedPreferences, modelId: String, tag: String?) {
        prefs.edit()
            .putString("chat_llm_engine", "CLOUD")
            .putString("chat_cloud_provider", "OPENROUTER")
            .putString(MODEL_KEY, modelId)
            .putString(PROVIDER_MODEL_KEY, modelId)
            .putString(PROVIDER_TAG_KEY, tag)
            .apply()
    }

    fun selected(prefs: SharedPreferences, modelId: String): String? =
        matchingTag(modelId, prefs.getString(PROVIDER_MODEL_KEY, null), prefs.getString(PROVIDER_TAG_KEY, null))

    fun apply(prefs: SharedPreferences, modelId: String, request: JSONObject) {
        val routing = provider(selected(prefs, modelId)) ?: return
        request.put("provider", routing)
    }

    fun matchingTag(modelId: String, savedModel: String?, tag: String?): String? {
        if (modelId != savedModel) {
            return null
        }
        return tag?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun provider(tag: String?): JSONObject? {
        if (tag.isNullOrBlank()) {
            return null
        }
        // 別の提供元へ切り替わって料金が変わることを防ぐ。
        return JSONObject()
            .put("only", JSONArray().put(tag))
            .put("order", JSONArray().put(tag))
            .put("allow_fallbacks", false)
    }
}
