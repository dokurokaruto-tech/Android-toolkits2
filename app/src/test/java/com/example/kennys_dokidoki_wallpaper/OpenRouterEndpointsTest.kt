package com.example.kennys_dokidoki_wallpaper

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class OpenRouterEndpointsTest {
    @Test
    fun parsesPricesAndHealth() {
        val endpoint = OpenRouterEndpoints.parse("""
            {"data":{"endpoints":[{
                "provider_name":"DeepInfra", "tag":"deepinfra/turbo",
                "pricing":{"prompt":"0.0000001","completion":"0.00000032",
                    "input_cache_read":"0.00000005","input_cache_write":"0.0000002","request":"0.001"},
                "uptime_last_5m":100,"uptime_last_30m":99.5,"uptime_last_1d":98.25,
                "latency_last_30m":{"p50":0.25},"throughput_last_30m":{"p50":45.2}
            }]}}
        """).single()
        assertEquals("DeepInfra", endpoint.name)
        assertEquals("deepinfra/turbo", endpoint.tag)
        assertEquals("0.1", OpenRouterEndpoints.perMillion(endpoint.promptPrice!!))
        assertEquals("0.32", OpenRouterEndpoints.perMillion(endpoint.completionPrice!!))
        assertEquals("0.05", OpenRouterEndpoints.perMillion(endpoint.cacheReadPrice!!))
        assertEquals("0.2", OpenRouterEndpoints.perMillion(endpoint.cacheWritePrice!!))
        assertEquals(BigDecimal("0.001"), endpoint.requestPrice)
        assertEquals(100.0, endpoint.uptime5m!!, 0.0)
        assertEquals(99.5, endpoint.uptime30m!!, 0.0)
        assertEquals(98.25, endpoint.uptime1d!!, 0.0)
        assertEquals(0.25, endpoint.latency!!, 0.0)
        assertEquals(45.2, endpoint.throughput!!, 0.0)
    }

    @Test
    fun missingMetricsStayUnknown() {
        val endpoint = OpenRouterEndpoints.parse("""
            {"data":{"endpoints":[{"provider_name":"Provider",
                "pricing":{"prompt":null,"completion":"invalid","request":"-1"},
                "uptime_last_5m":null,"uptime_last_30m":101,"uptime_last_1d":-1,
                "latency_last_30m":null,"throughput_last_30m":{"p50":"NaN"}
            }]}}
        """).single()
        assertNull(endpoint.tag)
        assertNull(endpoint.promptPrice)
        assertNull(endpoint.completionPrice)
        assertNull(endpoint.requestPrice)
        assertNull(endpoint.uptime5m)
        assertNull(endpoint.uptime30m)
        assertNull(endpoint.uptime1d)
        assertNull(endpoint.latency)
        assertNull(endpoint.throughput)
    }

    @Test
    fun preservesZeros() {
        val endpoint = OpenRouterEndpoints.parse("""
            {"data":{"endpoints":[{"provider_name":"Free","tag":"free",
                "pricing":{"prompt":"0","completion":0},"uptime_last_30m":0
            }]}}
        """).single()
        assertEquals("0", OpenRouterEndpoints.perMillion(endpoint.promptPrice!!))
        assertEquals("0", OpenRouterEndpoints.perMillion(endpoint.completionPrice!!))
        assertEquals(0.0, endpoint.uptime30m!!, 0.0)
    }

    @Test
    fun keepsEndpointVariants() {
        val endpoints = OpenRouterEndpoints.parse("""
            {"data":{"endpoints":[
                {"provider_name":"Google","tag":"google-vertex/us-east5"},
                {"provider_name":"Google","tag":"google-vertex/us-central1"}
            ]}}
        """)
        assertEquals(listOf("google-vertex/us-east5", "google-vertex/us-central1"), endpoints.map { it.tag })
    }

    @Test
    fun smallPricesStayNonzero() {
        assertEquals("0.0001", OpenRouterEndpoints.perMillion(BigDecimal("0.0000000001")))
    }

    @Test
    fun emptyEndpointsRemainEmpty() {
        assertTrue(OpenRouterEndpoints.parse("""{"data":{"endpoints":[]}}""").isEmpty())
    }

    @Test(expected = JSONException::class)
    fun malformedResponseFails() {
        OpenRouterEndpoints.parse("""{"data":{}}""")
    }
}
