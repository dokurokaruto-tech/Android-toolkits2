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

    private fun line(body: String, category: String = "", on: Boolean = true) =
        PersonaGroupPolicy.Line(
            PersonaPoolEntry(id = body, body = body, categoryId = category),
            on
        )

    @Test
    fun groupsFollowCategoryOrder() {
        val lines = listOf(line("学生", "c1"), line("敬語", "c3"), line("18歳", "c2"))
        val groups = PersonaGroupPolicy.groups(lines, categories)
        assertEquals(listOf("c1", "c2", "c3"), groups.map { it.id })
        assertEquals(listOf("学生"), groups.first().lines.map { it.entry.body })
        assertEquals(listOf("敬語"), groups.last().lines.map { it.entry.body })
    }

    @Test
    fun emptyCategoriesStayAsAddTargets() {
        val groups = PersonaGroupPolicy.groups(listOf(line("学生", "c1")), categories)
        assertEquals(3, groups.size)
        assertTrue(groups[1].lines.isEmpty())
    }

    @Test
    fun unboundAndDanglingLinesFallToUngroupedAtTheEnd() {
        val lines = listOf(line("古い行", "gone"), line("枠の無い行"), line("学生", "c1"))
        val groups = PersonaGroupPolicy.groups(lines, categories)
        assertEquals(listOf("c1", "c2", "c3", PersonaGroupPolicy.UNGROUPED_ID), groups.map { it.id })
        assertEquals(PersonaGroupPolicy.UNGROUPED_LABEL, groups.last().name)
        assertEquals(listOf("古い行", "枠の無い行"), groups.last().lines.map { it.entry.body })
    }

    @Test
    fun summaryCountsThisPersonasSwitches() {
        val group = PersonaGroupPolicy.groups(
            listOf(line("a", "c1", on = true), line("b", "c1", on = false)),
            categories
        ).first()
        assertEquals("2件中 1件が有効", PersonaGroupPolicy.summary(group))
        assertEquals("0件", PersonaGroupPolicy.summary(PersonaGroupPolicy.Group(looks.id, looks.name)))
    }

    @Test
    fun dropOnHeaderEntersThatGroupAtTheTop() {
        val rows = PersonaGroupPolicy.rows(listOf(line("学生", "c1")), categories)
        // 行: 0=H(c1) 1=学生 2=H(c2) 3=H(c3)
        val insertAt = PersonaGroupPolicy.dropIndex(rows, from = 1, to = 3)
        assertEquals(3, insertAt)

        val moved = moveInto(rows, from = 1, to = insertAt)
        assertEquals(listOf("学生" to style.id), PersonaGroupPolicy.arrangement(moved))
    }

    @Test
    fun dropOnRowUsesThatRowPosition() {
        val rows = PersonaGroupPolicy.rows(
            listOf(line("a", "c1"), line("b", "c1"), line("c", "c2")),
            categories
        )
        // c を a の位置へ。除けた分を差し引いて1つ前に入る
        val insertAt = PersonaGroupPolicy.dropIndex(rows, from = 4, to = 1)
        assertEquals(1, insertAt)

        val moved = moveInto(rows, from = 4, to = insertAt)
        assertEquals(listOf("c", "a", "b"), PersonaGroupPolicy.flatten(moved).map { it.entry.body })
        assertEquals(
            listOf("c" to social.id, "a" to social.id, "b" to social.id),
            PersonaGroupPolicy.arrangement(moved)
        )
    }

    @Test
    fun arrangementSkipsHeaders() {
        val rows = PersonaGroupPolicy.rows(listOf(line("a", "c1")), categories)
        assertEquals(1, PersonaGroupPolicy.arrangement(rows).size)
    }

    @Test
    fun dropIndexIgnoresMissingTarget() {
        val rows = PersonaGroupPolicy.rows(listOf(line("a", "c1")), categories)
        assertNull(PersonaGroupPolicy.dropIndex(rows, from = 1, to = 99))
    }

    @Test
    fun rowsAreHeaderThenEntriesPerGroup() {
        val rows = PersonaGroupPolicy.rows(listOf(line("a", "c1")), listOf(social))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is Row.Header)
        assertTrue(rows[1] is Row.Entry)
    }

    /** 行を1つ引いて落とした後の列表。アダプターのドラッグと同じ組み替えをする。 */
    private fun moveInto(rows: List<Row>, from: Int, to: Int): MutableList<Row> {
        val next = rows.toMutableList()
        val entry = next.removeAt(from) as Row.Entry
        val group = PersonaGroupPolicy.groupBefore(next, to) ?: entry.group
        next.add(to, Row.Entry(group, entry.line))
        return next
    }
}
