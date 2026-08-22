package com.example.kennys_dokidoki_wallpaper

import java.net.URLDecoder

/**
 * 閲覧中の生成画像を、クエリの token が変わっても同じ画像として扱うための識別子。
 */
object GeneratedImageIdentity {
    private val FILE_PATH = Regex("""/api/v1/files/([^/?#]+)/([^/?#]+)""")
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    data class RemoteRef(val date: String, val name: String)

    fun canonicalKey(uriString: String?): String {
        if (uriString.isNullOrBlank()) return ""
        remoteRef(uriString)?.let { return key(it) }
        return stripQueryAndFragment(uriString)
    }

    fun key(ref: RemoteRef): String = "generated:${ref.date}/${ref.name}"

    fun remoteRef(uriString: String?): RemoteRef? {
        if (uriString.isNullOrBlank()) return null
        val path = stripQueryAndFragment(uriString)
        val match = FILE_PATH.find(path) ?: return null
        val date = decode(match.groupValues[1])
        val name = decode(match.groupValues[2])
        if (!DATE.matches(date) || name.isBlank()) return null
        return RemoteRef(date, name)
    }

    fun stripQueryAndFragment(uriString: String): String =
        uriString.substringBefore('?').substringBefore('#')

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}
