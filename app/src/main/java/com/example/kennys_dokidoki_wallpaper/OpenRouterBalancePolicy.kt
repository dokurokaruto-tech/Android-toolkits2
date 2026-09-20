package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

internal object OpenRouterBalancePolicy {
    const val CREDIT_LOADING = "クレジット確認中…"
    const val CREDIT_FAILED = "クレジット取得失敗"
    const val KEY_MISSING = "APIキー未設定"
    private const val FREE_SUFFIX = ":free"
    private const val FREE_ROUTER = "openrouter/free"
    private const val CREDIT_DIGITS = 4
    private const val MIN_DISPLAY_CREDIT = 0.0001

    fun isFree(modelId: String, cachedModels: String?): Boolean {
        if (modelId.endsWith(FREE_SUFFIX) || modelId == FREE_ROUTER) {
            return true
        }
        if (cachedModels.isNullOrBlank()) {
            return false
        }
        return try {
            val models = JSONObject(cachedModels).getJSONArray("data")
            for (index in 0 until models.length()) {
                val model = models.getJSONObject(index)
                if (model.optString("id") != modelId) {
                    continue
                }
                val pricing = model.optJSONObject("pricing") ?: return false
                return pricing.optDouble("prompt", Double.NaN) == 0.0 &&
                    pricing.optDouble("completion", Double.NaN) == 0.0 &&
                    pricing.optDouble("request", 0.0) == 0.0
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun creditLine(remaining: Double?): String {
        if (remaining == null || !remaining.isFinite()) {
            return CREDIT_FAILED
        }
        if (remaining > 0 && remaining < MIN_DISPLAY_CREDIT) {
            return "残り $0.0001 未満"
        }
        val amount = BigDecimal.valueOf(remaining.coerceAtLeast(0.0))
            .setScale(CREDIT_DIGITS, RoundingMode.DOWN).toPlainString()
        return "残り $$amount"
    }

    fun parseCredits(json: String): Pair<Double, Double>? = try {
        val data = JSONObject(json).getJSONObject("data")
        val total = data.getDouble("total_credits")
        val used = data.getDouble("total_usage")
        if (total.isFinite() && used.isFinite() && total >= 0 && used >= 0) {
            total to used
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}
