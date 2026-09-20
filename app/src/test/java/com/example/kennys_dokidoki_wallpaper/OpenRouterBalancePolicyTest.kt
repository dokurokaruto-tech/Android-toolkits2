package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OpenRouterBalancePolicyTest {
    @Test
    fun paidModelUsesCredits() {
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", models("0.000001", "0.000002")))
        assertEquals("残り $12.3456", OpenRouterBalancePolicy.creditLine(12.34567))
    }

    @Test
    fun freeModelsKeepCounts() {
        assertTrue(OpenRouterBalancePolicy.isFree("vendor/paid", models("0", "0")))
        assertTrue(OpenRouterBalancePolicy.isFree("vendor/model:free", null))
        assertTrue(OpenRouterBalancePolicy.isFree("openrouter/free", null))
        assertEquals("残り 12 回", TagAiGenerateCopy.usageLine(38, 50))
    }

    @Test
    fun unknownPriceIsNotFree() {
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", null))
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", "invalid"))
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", """{"data":[{"id":"vendor/paid","pricing":{}}]}"""))
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", models("0", "0.001")))
        assertFalse(OpenRouterBalancePolicy.isFree("vendor/paid", models("0", "0", "0.01")))
    }

    @Test
    fun creditErrorsAreNotZero() {
        assertEquals(OpenRouterBalancePolicy.CREDIT_FAILED, OpenRouterBalancePolicy.creditLine(null))
        assertEquals(OpenRouterBalancePolicy.CREDIT_FAILED, OpenRouterBalancePolicy.creditLine(Double.NaN))
        assertEquals(OpenRouterBalancePolicy.CREDIT_FAILED, OpenRouterBalancePolicy.creditLine(Double.POSITIVE_INFINITY))
        assertEquals("残り $0.0000", OpenRouterBalancePolicy.creditLine(0.0))
        assertEquals("残り $0.0000", OpenRouterBalancePolicy.creditLine(-1.0))
        assertEquals("残り $0.0001 未満", OpenRouterBalancePolicy.creditLine(0.00001))
    }

    @Test
    fun creditFormatIsLocaleSafe() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("残り $1.2500", OpenRouterBalancePolicy.creditLine(1.25))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun validatesCreditResponse() {
        assertEquals(10.0 to 2.5, OpenRouterBalancePolicy.parseCredits("""{"data":{"total_credits":10,"total_usage":2.5}}"""))
        assertEquals(0.0 to 0.0, OpenRouterBalancePolicy.parseCredits("""{"data":{"total_credits":0,"total_usage":0}}"""))
        assertNull(OpenRouterBalancePolicy.parseCredits("""{"data":{}}"""))
        assertNull(OpenRouterBalancePolicy.parseCredits("""{"data":{"total_credits":10}}"""))
        assertNull(OpenRouterBalancePolicy.parseCredits("""{"data":{"total_credits":"NaN","total_usage":0}}"""))
        assertNull(OpenRouterBalancePolicy.parseCredits("invalid"))
    }

    private fun models(prompt: String, completion: String, request: String = "0"): String =
        """{"data":[{"id":"vendor/paid","pricing":{"prompt":"$prompt","completion":"$completion","request":"$request"}}]}"""
}
