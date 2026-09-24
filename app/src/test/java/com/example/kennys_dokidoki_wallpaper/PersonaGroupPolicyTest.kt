package com.example.kennys_dokidoki_wallpaper

import com.example.kennys_dokidoki_wallpaper.PersonaGroupPolicy.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaGroupPolicyTest {

    private val social = PersonaCategory(id = "c1", name = "社会的な立場")
    private val looks = PersonaCategory(id = "c2", name = "容姿・年齢")
    private val style = PersonaCategory(id = "c3", name = "文章の基本構成")
    private val categories = listOf(social, looks, style)

    private fun item(body: String) = PersonaItem(id = body, content = body)

    /** プール側が持つ「文 → 枠」を模した解決関数。 */
    private fun resolver(vararg pairs: Pair<String, String>): (PersonaItem) -> String {
        val bindings = pairs.toMap()
        return { target -> PersonaGroupPolicy.categoryOf(bindings, target.content) }
    }

    @Test
    fun categoryLookupIgnoresSurroundingSpaces() {
        val bindings = mapOf("学生" to social.id)
        assertEquals(social.id, PersonaGroupPolicy.categoryOf(bindings, " 学生 "))
        assertEquals(PersonaGroupPolicy.UNGROUPED_ID, PersonaGroupPolicy.categoryOf(bindings, "他の文"))
    }

    @Test
    fun groupsFollowCategoryOrder() {
        val items = listOf(item("学生"), item("敬語"), item("18歳"))
        val groups = PersonaGroupPolicy.groups(items, categories, resolver("学生" to "c1", "敬語" to "c3", "18歳" to "c2"))
        assertEquals(listOf("c1", "c2", "c3"), groups.map { it.id })
        assertEquals(listOf("学生"), groups.first().items.map { it.content })
        assertEquals(listOf("敬語"), groups.last().items.map { it.content })
    }

    @Test
    fun emptyCategoriesStayAsAddTargets() {
        val groups = PersonaGroupPolicy.groups(listOf(item("学生")), categories, resolver("学生" to "c1"))
        assertEquals(3, groups.size)
        assertEquals(0, groups[1].items.size)
    }

    @Test
    fun unboundAndDanglingBodiesFallToUngroupedAtTheEnd() {
        val items = listOf(item("古い指示"), item("枠の無い文"), item("学生"))
        val groups = PersonaGroupPolicy.groups(items, categories, resolver("学生" to "c1", "古い指示" to "gone"))
        assertEquals(listOf("c1", "c2", "c3", PersonaGroupPolicy.UNGROUPED_ID), groups.map { it.id })
        assertEquals(PersonaGroupPolicy.UNGROUPED_LABEL, groups.last().name)
        assertEquals(listOf("古い指示", "枠の無い文"), groups.last().items.map { it.content })
    }

    @Test
    fun oneBindingServesEveryPersona() {
        // 同じ文なら、どのペルソナの items から数えても同じ枠に寄る
        val resolve = resolver("学生" to "c2")
        val alone = PersonaGroupPolicy.groups(listOf(item("学生")), categories, resolve)
        val shared = PersonaGroupPolicy.groups(listOf(item("学生"), item("敬語")), categories, resolve)
        assertEquals(
            alone.first { it.items.isNotEmpty() }.id,
            shared.first { it.items.isNotEmpty() }.id
        )
        assertEquals(looks.id, alone.first { it.items.isNotEmpty() }.id)
    }

    @Test
    fun dropOnHeaderEntersThatGroupAtTheTop() {
        val rows = PersonaGroupPolicy.rows(listOf(item("学生")), categories, resolver("学生" to "c1"))
        // 行: 0=H(c1) 1=学生 2=H(c2) 3=H(c3)
        val insertAt = PersonaGroupPolicy.dropIndex(rows, from = 1, to = 3)
        assertEquals(3, insertAt)

        val moved = moveInto(rows, from = 1, to = insertAt)
        assertEquals(mapOf("学生" to style.id), PersonaGroupPolicy.bindings(moved))
    }

    @Test
    fun dropOnRowUsesThatRowPosition() {
        val items = listOf(item("a"), item("b"), item("c"))
        val rows = PersonaGroupPolicy.rows(items, categories, resolver("a" to "c1", "b" to "c1", "c" to "c2"))
        // c を a の位置へ。除けた分を差し引いて1つ前に入る
        val insertAt = PersonaGroupPolicy.dropIndex(rows, from = 4, to = 1)
        assertEquals(1, insertAt)

        val moved = moveInto(rows, from = 4, to = insertAt)
        assertEquals(listOf("c", "a", "b"), PersonaGroupPolicy.flatten(moved).map { it.content })
        assertEquals(
            mapOf("a" to social.id, "b" to social.id, "c" to social.id),
            PersonaGroupPolicy.bindings(moved)
        )
    }

    @Test
    fun dropIndexIgnoresMissingTarget() {
        val rows = PersonaGroupPolicy.rows(listOf(item("a")), categories, resolver("a" to "c1"))
        assertNull(PersonaGroupPolicy.dropIndex(rows, from = 1, to = 99))
    }

    @Test
    fun rowsAreHeaderThenEntriesPerGroup() {
        val rows = PersonaGroupPolicy.rows(listOf(item("a")), listOf(social), resolver("a" to "c1"))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is Row.Header)
        assertTrue(rows[1] is Row.Entry)
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

    /** 行を1つ引いて落とした後の列表。アダプターのドラッグと同じ組み替えをする。 */
    private fun moveInto(rows: List<Row>, from: Int, to: Int): MutableList<Row> {
        val next = rows.toMutableList()
        val entry = next.removeAt(from) as Row.Entry
        val group = PersonaGroupPolicy.groupBefore(next, to) ?: entry.group
        next.add(to, Row.Entry(group, entry.item))
        return next
    }
}
