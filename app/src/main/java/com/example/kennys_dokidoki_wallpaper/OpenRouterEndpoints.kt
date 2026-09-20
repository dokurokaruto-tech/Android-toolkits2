package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import java.math.BigDecimal

internal data class OpenRouterEndpoint(
    val name: String,
    val tag: String?,
    val promptPrice: BigDecimal?,
    val completionPrice: BigDecimal?,
    val cacheReadPrice: BigDecimal?,
    val cacheWritePrice: BigDecimal?,
    val requestPrice: BigDecimal?,
    val uptime5m: Double?,
    val uptime30m: Double?,
    val uptime1d: Double?,
    val latency: Double?,
    val throughput: Double?
)

internal object OpenRouterEndpoints {
    private val TOKENS_PER_MILLION = BigDecimal("1000000")
    private const val MAX_PERCENT = 100.0

    fun parse(json: String): List<OpenRouterEndpoint> {
        val endpoints = JSONObject(json).getJSONObject("data").getJSONArray("endpoints")
        return buildList {
            for (index in 0 until endpoints.length()) {
                val endpoint = endpoints.optJSONObject(index) ?: continue
                val tag = endpoint.text("tag")
                val name = endpoint.text("provider_name") ?: endpoint.text("name") ?: tag ?: continue
                val pricing = endpoint.optJSONObject("pricing")
                add(OpenRouterEndpoint(
                    name = name,
                    tag = tag,
                    promptPrice = pricing?.price("prompt"),
                    completionPrice = pricing?.price("completion"),
                    cacheReadPrice = pricing?.price("input_cache_read"),
                    cacheWritePrice = pricing?.price("input_cache_write"),
                    requestPrice = pricing?.price("request"),
                    uptime5m = endpoint.percent("uptime_last_5m"),
                    uptime30m = endpoint.percent("uptime_last_30m"),
                    uptime1d = endpoint.percent("uptime_last_1d"),
                    latency = endpoint.optJSONObject("latency_last_30m")?.metric("p50"),
                    throughput = endpoint.optJSONObject("throughput_last_30m")?.metric("p50")
                ))
            }
        }
    }

    fun perMillion(price: BigDecimal): String =
        price.multiply(TOKENS_PER_MILLION).stripTrailingZeros().toPlainString()

    private fun JSONObject.text(key: String): String? {
        if (isNull(key)) {
            return null
        }
        return optString(key).trim().takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.price(key: String): BigDecimal? =
        text(key)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

    private fun JSONObject.metric(key: String): Double? =
        optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0 }

    private fun JSONObject.percent(key: String): Double? =
        metric(key)?.takeIf { it <= MAX_PERCENT }
}
