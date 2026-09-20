package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JevGeniePolicyTest {
    private val cards = listOf(
        JevGenieCardRef("id1", "制服", "衣装", "school uniform, pleated skirt", ""),
        JevGenieCardRef("id2", "Smile", "表情", "smile", "frown")
    )
    private val tags = listOf(JevGenieTagRef("お腹", "腹部を見せる指示"))

    @Test
    fun defaultModelIsFreeAlias() {
        assertEquals("~typesafe/jev-latest", JevGeniePolicy.DEFAULT_MODEL)
        assertEquals("~typesafe/jev-latest", JevElementPolicy.DEFAULT_JEV)
    }

    @Test
    fun parsesCardPlanInsideFences() {
        val raw = "```json\n" +
            """{"tool":"edit_prompt_card","target":"制服","reply":"更新します",""" +
            """"after":{"main_prompt":"school uniform, bare midriff"}}""" +
            "\n```"
        val plan = JevGeniePolicy.parse(raw, cards, tags) as JevGeniePlan.EditCard
        assertEquals("id1", plan.target.id)
        assertEquals("school uniform, bare midriff", plan.newMain)
        assertEquals("", plan.newNegative)
        assertEquals("更新します", plan.reply)
    }

    @Test
    fun cardPlanKeepsNegativeWhenOmitted() {
        val raw = """{"tool":"edit_prompt_card","target":"Smile","after":""" +
            """{"main_prompt":"grin","negative_prompt":"sad face"}}"""
        val plan = JevGeniePolicy.parse(raw, cards, tags) as JevGeniePlan.EditCard
        assertEquals("sad face", plan.newNegative)
    }

    @Test
    fun targetMatchIgnoresCase() {
        val raw = """{"tool":"edit_prompt_card","target":"smile","after":""" +
            """{"main_prompt":"grin"}}"""
        val plan = JevGeniePolicy.parse(raw, cards, tags) as JevGeniePlan.EditCard
        assertEquals("id2", plan.target.id)
    }

    @Test
    fun parsesTagPlan() {
        val raw = """{"tool":"edit_tag_prompt","target":"お腹","after":""" +
            """{"text":"お腹の素肌を少し見せる"}}"""
        val plan = JevGeniePlan.EditTag::class.let {
            JevGeniePolicy.parse(raw, cards, tags) as JevGeniePlan.EditTag
        }
        assertEquals("お腹", plan.target.name)
        assertEquals("お腹の素肌を少し見せる", plan.newText)
        assertTrue(plan.reply.isNotEmpty())
    }

    @Test
    fun noneToolBecomesTalk() {
        val plan = JevGeniePolicy.parse(
            """{"tool":"none","reply":"どのカードですか？"}""", cards, tags)
        assertEquals("どのカードですか？", (plan as JevGeniePlan.Talk).reply)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTargetIsRejected() {
        JevGeniePolicy.parse(
            """{"tool":"edit_prompt_card","target":"存在しない","after":""" +
                """{"main_prompt":"x"}}""", cards, tags)
    }

    @Test
    fun requestBodyBuildsMessageChain() {
        val system = JevGeniePolicy.systemPrompt(cards, tags)
        assertTrue(system.contains("衣装 / 制服"))
        assertTrue(system.contains("お腹"))
        val body = JevGeniePolicy.requestBody(
            "~typesafe/jev-latest", system, listOf("user" to "a", "assistant" to "b"), "願い")
        assertEquals("~typesafe/jev-latest", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("願い", messages.getJSONObject(3).getString("content"))
    }
}
