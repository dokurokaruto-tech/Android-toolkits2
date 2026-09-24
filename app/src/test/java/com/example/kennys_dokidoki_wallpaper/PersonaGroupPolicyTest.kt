package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.PersonaGroupPolicy.Row
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGroupPolicyTest {

    private val social = PersonaCategory(id = "c1", name = "社会的な立場")
    private val looks = PersonaCategory(id = "c2", name = "容姿・年齢")
    private val style = PersonaCategory(id = "c3", name = "文章の基本構成")
    private val categories = listOf(social, looks, style)

    private fun item(body: String, category: String = "") =
        PersonaItem(id = body, content = body, categoryId = category)

    @Test
    fun groupsFollowCategoryOrder() {
        val items = listOf(item("学生", social.id), item("敬語", style.id), item("18歳", looks.id))
        val groups = PersonaGroupPolicy.groups(items, categories)
        assertEquals(listOf("c1", "c2", "c3"), groups.map { it.id })
        assertEquals(listOf("学生"), groups.first().items.map { it.content })
        assertEquals(listOf("敬語"), groups.last().items.map { it.content })
    }

    @Test
    fun emptyCategoriesStayAsAddTargets() {
        val groups = PersonaGroupPolicy.groups(listOf(item("学生", social.id)), categories)
        assertEquals(3, groups.size)
        assertEquals(0, groups[1].items.size)
    }

    @Test
    fun unknownCategoryFallsToUngroupedAtTheEnd() {
        val items = listOf(item("古い指示", "gone"), item("学生", social.id))
        val groups = PersonaGroupPolicy.groups(items, categories)
        assertEquals(listOf("c1", "c2", "c3", PersonaGroupPolicy.UNGROUPED_ID), groups.map { it.id })
        assertEquals(PersonaGroupPolicy.UNGROUPED_LABEL, groups.last().name)
    }

    @Test
    fun everyCategoryReadsTheSameItems() {
        // 枠で候補を絞らないので、全項目はどの枠の下の並びにも現れる
        val items = listOf(item("a", social.id), item("b", looks.id), item("c", style.id))
        val flattened = PersonaGroupPolicy.flatten(PersonaGroupPolicy.rows(items, categories))
        assertEquals(items.map { it.content }, flattened.map { it.content })
    }

    @Test
    fun dropOnHeaderEntersThatGroupAtTheTop() {
        val rows = PersonaGroupPolicy.rows(listOf(item("学生", social.id)), categories)
        // 行: 0=H(c1) 1=学生 2=H(c2) 3=H(c3)
        val insertAt = PersonaGroupPolicy.dropIndex(rows, from = 1, to = 3)
        assertEquals(3, insertAt)

        val moved = rows.toMutableList().apply {
            val entry = removeAt(1) as PersonaGroupPolicy.Row.Entry
            add(insertAt, PersonaGroupPolicy.Row.Entry(PersonaGroupPolicy.groupBefore(this, insertAt), entry.item))
        }
        assertEquals(style.id, PersonaGroupPolicy.flatten(moved).single().categoryId)
    }

    @Test
    fun dropOnRowUsesThatRowPosition() {
        val items = listOf(item("a", social.id), item("b", social.id), item("c", looks.id))
        val rows = PersonaGroupPolicy.rows(items, categories)
        // c を a の位置へ。除けた分を差し引いて1つ前に入る
        assertEquals(1, PersonaGroupPolicy.dropIndex(rows, from = 4, to = 1))

        val moved = rows.toMutableList().apply {
            val entry = removeAt(4) as PersonaGroupPolicy.Row.Entry
            val group = PersonaGroupPolicy.groupBefore(this, 1) ?: entry.group
            add(1, PersonaGroupPolicy.Row.Entry(group, entry.item))
        }
        assertEquals(listOf("c", "a", "b"), PersonaGroupPolicy.flatten(moved).map { it.content })
        assertEquals(listOf(social.id, social.id, social.id), PersonaGroupPolicy.flatten(moved).map { it.categoryId })
    }

    @Test
    fun dropIndexIgnoresMissingTarget() {
        val rows = PersonaGroupPolicy.rows(listOf(item("a", social.id)), categories)
        assertNull(PersonaGroupPolicy.dropIndex(rows, from = 1, to = 99))
    }

    @Test
    fun summaryCountsOnlyEnabled() {
        val group = PersonaGroupPolicy.Group(
            social.id,
            social.name,
            mutableListOf(item("a"), item("b").apply { isEnabled = false })
        )
        assertEquals("2件中 1件が有効", PersonaGroupPolicy.summary(group))
        assertEquals("0件", PersonaGroupPolicy.summary(PersonaGroupPolicy.Group(looks.id, looks.name)))
    }

    @Test
    fun rowsAreHeaderThenEntriesPerGroup() {
        val rows = PersonaGroupPolicy.rows(listOf(item("a", social.id)), listOf(social))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is Row.Header)
        assertTrue(rows[1] is Row.Entry)
    }

    @Test
    fun itemKeepsItsCategoryThroughJson() {
        val json = PersonaItem(id = "x", content = "学生", categoryId = "c1").toJson()
        assertEquals("c1", PersonaItem.fromJson(json).categoryId)
    }

    @Test
    fun legacyItemWithoutCategoryReadsAsUngrouped() {
        val json = JSONObject().put("id", "x").put("content", "古い指示")
        assertEquals(PersonaGroupPolicy.UNGROUPED_ID, PersonaItem.fromJson(json).categoryId)
    }
}
