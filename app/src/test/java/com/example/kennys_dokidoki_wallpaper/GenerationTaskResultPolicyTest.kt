package com.example.kennys_dokidoki_wallpaper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationTaskResultPolicyTest {
    private val card = ThumbnailBindPolicy.Target.card("card-1")
    private val preset = ThumbnailBindPolicy.Target.preset("preset-1")

    private fun result(index: Int, purpose: String, target: ThumbnailBindPolicy.Target? = null) =
        AgentTaskResult(index, purpose, "https://pc/result-$index", target, JSONObject())

    @Test
    fun mixedResultsStayIndependent() {
        val image = result(101, "image")
        val thumbnail = result(100, "thumbnail", card)
        val bindings = GenerationTaskResultPolicy.thumbnails(listOf(thumbnail, image), listOf(preset))
        assertEquals(mapOf(card to thumbnail), bindings)
        assertEquals(listOf(image), GenerationTaskResultPolicy.images(listOf(thumbnail, image)))
    }

    @Test
    fun newestRequestWinsPerTarget() {
        val old = result(0, "thumbnail", card)
        val new = result(100, "thumbnail", card)
        val other = result(101, "thumbnail", preset)
        assertEquals(mapOf(card to new, preset to other),
            GenerationTaskResultPolicy.thumbnails(listOf(new, other, old), emptyList()))
    }

    @Test
    fun failedTasksDoNotShiftTargets() {
        val second = result(1, "thumbnail")
        assertEquals(mapOf(preset to second),
            GenerationTaskResultPolicy.thumbnails(listOf(second), listOf(card, preset)))
    }

    @Test
    fun missingTargetIsNotGuessed() {
        assertTrue(GenerationTaskResultPolicy.thumbnails(listOf(result(100, "thumbnail")), listOf(card)).isEmpty())
        assertTrue(GenerationTaskResultPolicy.images(listOf(result(1, "unknown"))).isEmpty())
    }
}
