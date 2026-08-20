package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AtomicFilesTest {

    @Test
    fun `write then read returns the same content`() {
        val dir = createTempDir()
        val file = File(dir, "chat.json")
        AtomicFiles.writeUtf8(file, """{"hello":"ケニー"}""")
        assertEquals("""{"hello":"ケニー"}""", AtomicFiles.readUtf8(file))
        assertTrue(file.exists())
    }

    @Test
    fun `overwrite replaces previous content`() {
        val dir = createTempDir()
        val file = File(dir, "links.json")
        AtomicFiles.writeUtf8(file, "first")
        AtomicFiles.writeUtf8(file, "second")
        assertEquals("second", AtomicFiles.readUtf8(file))
    }

    @Test
    fun `missing file returns null`() {
        val dir = createTempDir()
        assertNull(AtomicFiles.readUtf8(File(dir, "missing.json")))
    }
}
