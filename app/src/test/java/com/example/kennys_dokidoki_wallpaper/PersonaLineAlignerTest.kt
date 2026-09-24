package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaLineAlignerTest {

    private fun entry(id: String, body: String, category: String = "") =
        PersonaPoolEntry(id = id, body = body, categoryId = category)

    private fun item(id: String, body: String, on: Boolean = true) =
        PersonaItem(id = id, content = body, isEnabled = on)

    private fun persona(id: String, vararg items: PersonaItem) =
        UserPersona(id = id, name = id, items = items.toMutableList())

    @Test
    fun sameTextBecomesOneSharedLine() {
        val pool = mutableListOf(entry("p1", "甘える", "c1"))
        val first = persona("A", item("x1", "甘える"), item("x2", "敬語"))
        val second = persona("B", item("y1", "敬語", on = false))

        val result = PersonaLineAligner.align(pool, listOf(first, second), mapOf("x2" to "c2"), defaultOn = false)

        assertTrue(result.poolChanged)
        assertEquals(listOf("甘える", "敬語"), pool.map { it.body })
        assertEquals(listOf("c1", "c2"), pool.map { it.categoryId })
        // 同じ行を指すようになり、on/off だけがペルソナごとに違う
        assertEquals(pool.map { it.id }, first.items.map { it.id })
        assertEquals(listOf(true, true), first.items.map { it.isEnabled })
        assertEquals(listOf(false, false), second.items.map { it.isEnabled })
    }

    @Test
    fun newLineTurnsOnForEveryoneAfterAlign() {
        val pool = mutableListOf(entry("p1", "甘える", "c1"), entry("p2", "敬語", "c2"))
        val first = persona("A", item("p1", "甘える"))
        val second = persona("B", item("p2", "敬語"))

        PersonaLineAligner.align(pool, listOf(first, second), defaultOn = true)

        assertEquals(listOf("p1", "p2"), first.items.map { it.id })
        assertEquals(listOf(true, true), first.items.map { it.isEnabled })
        assertEquals(listOf(true, true), second.items.map { it.isEnabled })
    }

    @Test
    fun editedCopyKeepsItsOwnText() {
        // 手元だけ直した文は、別の行として残り、元の行は他のペルソナが使い続ける
        val pool = mutableListOf(entry("p1", "甘える", "c1"))
        val persona = persona("A", item("p1", "甘えてくる"))

        PersonaLineAligner.align(pool, listOf(persona), defaultOn = false)

        assertEquals(listOf("甘える", "甘えてくる"), pool.map { it.body })
        assertEquals(listOf("甘えてくる"), persona.items.filter { it.isEnabled }.map { it.content })
    }

    @Test
    fun removedLineDisappearsFromEveryPersona() {
        val pool = mutableListOf(entry("p1", "甘える"))
        val first = persona("A", item("p1", "甘える"))
        val second = persona("B", item("p1", "甘える"))
        pool.removeAt(0)

        PersonaLineAligner.align(pool, listOf(first, second), defaultOn = true)

        assertTrue(first.items.isEmpty())
        assertTrue(second.items.isEmpty())
    }

    @Test
    fun blankRowsAreDropped() {
        val pool = mutableListOf(entry("p1", "甘える"))
        val persona = persona("A", item("x1", "  "), item("p1", "甘える"))

        val result = PersonaLineAligner.align(pool, listOf(persona), defaultOn = true)

        assertEquals(1, pool.size)
        assertEquals(listOf("p1"), persona.items.map { it.id })
        assertTrue(result.personasChanged)
    }

    @Test
    fun nothingChangesWhenAlreadyAligned() {
        val pool = mutableListOf(entry("p1", "甘える", "c1"))
        val persona = persona("A", item("p1", "甘える"))

        val result = PersonaLineAligner.align(pool, listOf(persona), defaultOn = true)

        assertEquals(PersonaLineAligner.Result(poolChanged = false, personasChanged = false), result)
    }
}
