package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSavePolicyTest {
    private val a = "http://pc/api/v1/files/2026-10-02/a.png?token=old"
    private val b = "http://pc/api/v1/files/2026-10-02/b.png"
    private val saved = setOf(GeneratedImageIdentity.canonicalKey(a))

    @Test
    fun savedSurvivesTokenChange() {
        assertEquals(ViewerSavePolicy.State.SAVED,
            ViewerSavePolicy.state(a.replace("old", "new"), saved, null))
    }

    @Test
    fun pageTurnDoesNotCarryBadge() {
        assertEquals(ViewerSavePolicy.State.READY, ViewerSavePolicy.state(b, saved, null))
        assertEquals(ViewerSavePolicy.State.CHECKING, ViewerSavePolicy.state(b, null, null))
    }

    @Test
    fun partialFileIsNotSavedYet() {
        assertEquals(ViewerSavePolicy.State.SAVING, ViewerSavePolicy.state(a, saved, a))
        assertEquals(ViewerSavePolicy.State.WAITING, ViewerSavePolicy.state(b, emptySet(), a))
    }

    @Test
    fun completionOnlyMarksSource() {
        assertEquals(ViewerSavePolicy.State.SAVED, ViewerSavePolicy.state(a, saved, null))
        assertEquals(ViewerSavePolicy.State.READY, ViewerSavePolicy.state(b, saved, null))
    }

    @Test
    fun removedFileEnablesRetry() {
        assertEquals(ViewerSavePolicy.State.READY, ViewerSavePolicy.state(a, emptySet(), null))
    }

    @Test
    fun localAndInvalidSources() {
        val local = "content://images/1"
        assertEquals(ViewerSavePolicy.State.SAVED, ViewerSavePolicy.state(local, setOf(local), null))
        assertEquals(ViewerSavePolicy.State.HIDDEN, ViewerSavePolicy.state(local, emptySet(), null))
        assertEquals(ViewerSavePolicy.State.HIDDEN, ViewerSavePolicy.state(null, emptySet(), null))
    }
}
