package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageDeletePolicyTest {
    @Test
    fun titleAsksForConfirmation() {
        assertEquals("本当に削除しますか？", GeneratedImageDeletePolicy.title(1))
        assertEquals("本当に 3 件を削除しますか？", GeneratedImageDeletePolicy.title(3))
    }

    @Test
    fun messageMentionsRemotePcWhenNeeded() {
        val one = GeneratedImageDeletePolicy.message(1, remote = true)
        val many = GeneratedImageDeletePolicy.message(4, remote = false)
        assertTrue(one.contains("PC"))
        assertTrue(many.contains("4件"))
        assertFalse(many.contains("PC"))
    }

    @Test
    fun deleteActionIsTheConfirmLabel() {
        assertEquals("削除する", GeneratedImageDeletePolicy.DELETE)
        assertEquals("キャンセル", GeneratedImageDeletePolicy.CANCEL)
    }
}
