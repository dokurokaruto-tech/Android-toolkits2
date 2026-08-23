package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentConnectionDiagnosisTest {
    @Test
    fun `classifies windows 10060 as timeout`() {
        val diagnosis = AgentConnectionClassifier.fromException(
            IOException("TimeoutError(10060, '接続済みの呼び出し先が一定の時間を過ぎても正しく応答しなかった')"),
            target = "http://192.168.1.23:3001",
            path = "/api/v1/jobs/abc"
        )
        assertEquals(AgentConnectionClassifier.TIMEOUT, diagnosis.code)
        assertTrue(diagnosis.displayText().contains("192.168.1.23:3001"))
    }

    @Test
    fun `classifies failed to connect after 5000ms as connect timeout`() {
        val diagnosis = AgentConnectionClassifier.fromException(
            IOException("SocketTimeoutException: failed to connect to /192.168.1.45 (port 3001) from /192.168.1.35 (port 56170) after 5000ms"),
            target = "http://192.168.1.45:3001",
            path = "/api/v1/health"
        )
        assertEquals(AgentConnectionClassifier.CONNECT_TIMEOUT, diagnosis.code)
        assertTrue(diagnosis.reason.contains("HTTP以前"))
        assertTrue(diagnosis.nextStep.contains("100.x"))
    }

    @Test
    fun `classifies connection refused`() {
        val diagnosis = AgentConnectionClassifier.fromException(
            IOException("failed to connect to /192.168.1.23 (port 3001): connect failed: ECONNREFUSED")
        )
        assertEquals(AgentConnectionClassifier.REFUSED, diagnosis.code)
    }

    @Test
    fun `classifies missing url`() {
        val diagnosis = AgentConnectionClassifier.fromException(IOException("PC生成エージェントのURLが未設定です"))
        assertEquals(AgentConnectionClassifier.URL_UNSET, diagnosis.code)
    }

    @Test
    fun `classifies auth and http`() {
        assertEquals(
            AgentConnectionClassifier.AUTH,
            AgentConnectionClassifier.fromException(IOException("PC生成エージェント HTTP 401: authentication required")).code
        )
        assertEquals(
            AgentConnectionClassifier.HTTP,
            AgentConnectionClassifier.fromException(IOException("PC生成エージェント HTTP 502: SD connection failed")).code
        )
    }

    @Test
    fun `health distinguishes agent ok from sd down`() {
        val ok = AgentConnectionClassifier.fromHealth(
            "android-toolkits-generation-agent", true, "http://192.168.1.23:3001"
        )
        assertEquals(AgentConnectionClassifier.OK, ok.code)
        val sd = AgentConnectionClassifier.fromHealth(
            "android-toolkits-generation-agent", false, "http://192.168.1.23:3001"
        )
        assertEquals(AgentConnectionClassifier.SD_DOWN, sd.code)
        val wrong = AgentConnectionClassifier.fromHealth("something-else", null, "http://127.0.0.1:7860")
        assertEquals(AgentConnectionClassifier.WRONG_SERVICE, wrong.code)
    }

    @Test
    fun `redacts token from urls`() {
        assertEquals(
            "http://pc:3001/api/v1/files/a.png?token=***",
            AgentConnectionClassifier.redactUrl("http://pc:3001/api/v1/files/a.png?token=secret")
        )
    }
}
