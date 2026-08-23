package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentNetworkPolicyTest {
    @Test
    fun `windows 10060 stays transient so generation is not aborted`() {
        val timeout = IOException(
            "PC生成エージェントとの通信に失敗しました: TimeoutError(10060, '接続済みの呼び出し先が一定の時間を過ぎても正しく応答しなかった')"
        )
        assertTrue(AgentNetworkPolicy.isTransient(timeout))
        assertFalse(AgentNetworkPolicy.shouldAbortMonitor(timeout, 20))
    }

    @Test
    fun `unexpected server errors still abort after enough failures`() {
        val boom = IllegalStateException("broken json")
        assertFalse(AgentNetworkPolicy.isTransient(boom))
        assertFalse(AgentNetworkPolicy.shouldAbortMonitor(boom, 3))
        assertTrue(AgentNetworkPolicy.shouldAbortMonitor(boom, 8))
    }

    @Test
    fun `backoff grows then caps`() {
        assertEquals(1000L, AgentNetworkPolicy.backoffMs(1))
        assertEquals(4000L, AgentNetworkPolicy.backoffMs(2))
        assertEquals(15_000L, AgentNetworkPolicy.backoffMs(20))
    }
}
