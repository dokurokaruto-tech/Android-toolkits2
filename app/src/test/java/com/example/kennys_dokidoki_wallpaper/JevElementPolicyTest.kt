package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JevElementPolicyTest {
    private val catalog = ElementCatalog(
        cards = listOf("表情", "背景"),
        tags = listOf("表情・仕草", "その他"))

    @Test
    fun endpoint_acceptsOfficialUrl() {
        assertEquals(JevElementPolicy.DEFAULT_ENDPOINT, JevElementPolicy.endpoint(" https://openrouter.ai/api/v1/systemone "))
    }

    @Test
    fun endpoint_rejectsOtherHosts() {
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("https://evil.example.com/api") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("http://openrouter.ai/api/v1/systemone") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("https://openrouter.ai.evil.com/api") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("https://user@openrouter.ai/api") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("https://openrouter.ai/api?token=x") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.endpoint("https://openrouter.ai/") }
    }

    @Test
    fun writer_rejectsJevModel() {
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.writer("typesafe/jev-1.13") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.writer("jev-1.13") }
        assertEquals("deepseek/deepseek-v4-flash:free",
            JevElementPolicy.writer(" deepseek/deepseek-v4-flash:free "))
    }

    @Test
    fun model_rejectsBlankAndSpaces() {
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.model("  ") }
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.model("bad model/id") }
    }

    @Test
    fun decisionBody_embedsStateAndOptions() {
        val body = JevElementPolicy.decisionBody("笑顔", catalog, "typesafe/jev-1.13")
        assertEquals("typesafe/jev-1.13", body.getString("model"))
        assertEquals("笑顔", body.getJSONObject("state").getString("element"))
        val card = body.getJSONObject("questions").getJSONObject("card_category")
        assertEquals("choice", card.getString("type"))
        assertEquals("表情", card.getJSONObject("criteria").getString("c0"))
        assertEquals("背景", card.getJSONObject("criteria").getString("c1"))
        assertEquals(true, card.getJSONObject("criteria").has("none"))
    }

    @Test
    fun decisionBody_rejectsTooManyCategories() {
        val huge = ElementCatalog(List(241) { "c$it" }, listOf("a"))
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.decisionBody("x", huge, "m")
        }
    }

    @Test
    fun category_parsesChosenIndexAndProbability() {
        val answer = JSONObject("""{"type":"choice","choice":"c1","probabilities":{"c0":0.2,"c1":0.8,"none":0.0}}""")
        val result = JevElementPolicy.category(answer, catalog.cards)
        assertEquals("背景", result.name)
        assertEquals(0.8, result.probability!!, 0.0001)
    }

    @Test
    fun category_mapsNoneToNull() {
        val answer = JSONObject("""{"type":"choice","choice":"none"}""")
        assertNull(JevElementPolicy.category(answer, catalog.tags).name)
    }

    @Test
    fun category_rejectsOutOfRange() {
        val answer = JSONObject("""{"type":"choice","choice":"c9"}""")
        assertThrows(IllegalArgumentException::class.java) { JevElementPolicy.category(answer, catalog.cards) }
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.category(JSONObject("""{"type":"noul","noul":0.5}"""), catalog.cards)
        }
    }

    @Test
    fun category_ignoresInvalidProbability() {
        val answer = JSONObject("""{"type":"choice","choice":"c0","probabilities":{"c0":1.5}}""")
        assertNull(JevElementPolicy.category(answer, catalog.cards).probability)
    }

    @Test
    fun parseText_readsStrictJson() {
        val text = JevElementPolicy.parseText(
            """{"main_prompt":"smile","negative_prompt":"","chat_instruction":"この場面では笑みを浮かべている。"}""")
        assertEquals("smile", text.main)
        assertEquals("", text.negative)
        assertEquals("この場面では笑みを浮かべている。", text.chat)
    }

    @Test
    fun parseText_rejectsMissingFields() {
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.parseText("""{"main_prompt":"","chat_instruction":"x"}""")
        }
        assertThrows(Exception::class.java) { JevElementPolicy.parseText("not json") }
    }

    @Test
    fun validate_enforcesBoundsAndDuplicates() {
        val ok = ElementDraft("笑顔", "表情", "表情・仕草",
            ElementText("smile", "", "この場面では笑みを浮かべている。"))
        JevElementPolicy.validate(ok, catalog, listOf("泣き顔"))

        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.validate(ok, catalog, listOf("笑顔"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.validate(ok.copy(cardCategory = "存在しない"), catalog, listOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.validate(ok.copy(name = "  "), catalog, listOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            JevElementPolicy.validate(ok.copy(text = ok.text.copy(main = "")), catalog, listOf())
        }
    }

    @Test
    fun decisionBody_sendsOnlyWordAndCategories() {
        val body = JevElementPolicy.decisionBody("笑顔", catalog, "typesafe/jev-1.13")
        val state = body.getJSONObject("state")
        assertEquals(1, state.length())
        val questions = body.getJSONObject("questions")
        assertEquals(true, questions.getJSONObject("card_category").getJSONObject("criteria").has("none"))
        // 分類に必要なのは要素と候補名だけ（サンプル一覧は送らない）
        assertEquals(false, state.has("card_categories"))
    }

    @Test
    fun writerBody_embedsInstructionAndWord() {
        val body = JevElementPolicy.writerBody("笑顔", "deepseek/deepseek-v4-flash:free", "指示書X")
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("指示書X", messages.getJSONObject(0).getString("content"))
        assertEquals(true, messages.getJSONObject(1).getString("content").contains("笑顔"))
    }

    @Test
    fun sameName_normalizesWidthAndCase() {
        assertEquals(true, JevElementPolicy.sameName("Ｅｇａｏ", "egao"))
        assertEquals(false, JevElementPolicy.sameName("笑顔", "泣き顔"))
    }
}
