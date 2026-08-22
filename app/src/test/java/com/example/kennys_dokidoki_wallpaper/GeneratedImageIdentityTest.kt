package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedImageIdentityTest {

    @Test
    fun `strips token so the same PC file stays one key`() {
        val withToken = "http://192.168.1.23:3001/api/v1/files/2026-08-22/GEN_one.png?token=secret"
        val otherToken = "http://192.168.1.23:3001/api/v1/files/2026-08-22/GEN_one.png?token=changed"
        assertEquals("generated:2026-08-22/GEN_one.png", GeneratedImageIdentity.canonicalKey(withToken))
        assertEquals(
            GeneratedImageIdentity.canonicalKey(withToken),
            GeneratedImageIdentity.canonicalKey(otherToken)
        )
    }

    @Test
    fun `decodes percent-encoded file names`() {
        val url = "http://pc:3001/api/v1/files/2026-08-22/GEN%20two.png"
        val ref = GeneratedImageIdentity.remoteRef(url)
        assertEquals("2026-08-22", ref?.date)
        assertEquals("GEN two.png", ref?.name)
        assertEquals("generated:2026-08-22/GEN two.png", GeneratedImageIdentity.canonicalKey(url))
    }

    @Test
    fun `local document uris keep their path without query`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3Agen/document/primary%3Agen%2F2026-08-22%2Fa.png"
        assertEquals(uri, GeneratedImageIdentity.canonicalKey(uri))
    }

    @Test
    fun `rejects non library paths`() {
        assertNull(GeneratedImageIdentity.remoteRef("http://pc:3001/api/v1/jobs/abc"))
        assertNull(GeneratedImageIdentity.remoteRef("http://pc:3001/api/v1/files/not-a-date/a.png"))
        assertEquals("", GeneratedImageIdentity.canonicalKey(null))
    }
}
