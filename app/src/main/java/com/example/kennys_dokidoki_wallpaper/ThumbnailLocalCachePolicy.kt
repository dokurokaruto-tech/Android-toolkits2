package com.example.kennys_dokidoki_wallpaper

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * カード／プリセットのサムネイルは、PCの圧縮JPEGを端末へ残してオフライン表示する。
 */
object ThumbnailLocalCachePolicy {
    const val DIR_NAME = "card_thumbnails"
    const val MAX_WIDTH = 480
    const val MAX_HEIGHT = 854
    const val JPEG_QUALITY = 72
    const val MAX_IN_FLIGHT = 2

    private val REMOTE_FILE = Regex(
        """/api/v1/(?:files|thumbnail-files|mobile-thumbnails)/([^/?#]+)/([^/?#]+)"""
    )
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MANAGED_NAME = Regex("""^(card|preset)_[A-Za-z0-9._-]+_[0-9a-f]+\.jpg$""")

    data class RemoteRef(val date: String, val name: String)

    fun isRemote(uri: String?): Boolean {
        val scheme = uri?.substringBefore(':')?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    fun needsLocalCopy(uri: String?): Boolean = isRemote(uri)

    fun stripQueryAndFragment(uri: String): String =
        uri.substringBefore('?').substringBefore('#')

    fun remoteRef(uri: String?): RemoteRef? {
        if (uri.isNullOrBlank()) return null
        val match = REMOTE_FILE.find(stripQueryAndFragment(uri)) ?: return null
        val date = decode(match.groupValues[1])
        val name = decode(match.groupValues[2])
        if (!DATE.matches(date) || name.isBlank()) return null
        return RemoteRef(date, name)
    }

    fun toMobileThumbnailUrl(remoteUrl: String): String? {
        val ref = remoteRef(remoteUrl) ?: return null
        val path = stripQueryAndFragment(remoteUrl)
        val replaced = path.replace(
            Regex("""/api/v1/(?:files|thumbnail-files|mobile-thumbnails)/[^/?#]+/[^/?#]+"""),
            "/api/v1/mobile-thumbnails/${encode(ref.date)}/${encode(ref.name)}"
        )
        return replaced + remoteUrl.removePrefix(path)
    }

    fun downloadUrls(remoteUrl: String): List<String> {
        val trimmed = remoteUrl.trim()
        if (trimmed.isEmpty()) return emptyList()
        return listOfNotNull(toMobileThumbnailUrl(trimmed), trimmed).distinct()
    }

    fun sourceKey(uri: String?): String {
        if (uri.isNullOrBlank()) return ""
        val ref = remoteRef(uri)
        val identity = if (ref != null) "${ref.date}/${ref.name}" else stripQueryAndFragment(uri)
        return Integer.toUnsignedString(identity.hashCode(), 16)
    }

    fun localSourceKey(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val name = uri.substringAfterLast('/').substringBefore('?')
        return Regex("""^(?:card|preset)_[A-Za-z0-9._-]+_([0-9a-f]+)\.jpg$""")
            .find(name)
            ?.groupValues
            ?.get(1)
    }

    fun localFileName(kind: String, id: String, sourceUrl: String): String =
        "${safeToken(kind)}_${safeToken(id)}_${sourceKey(sourceUrl)}.jpg"

    fun managedPrefix(kind: String, id: String): String =
        "${safeToken(kind)}_${safeToken(id)}_"

    fun isManagedFileName(name: String): Boolean = MANAGED_NAME.matches(name)

    fun shouldKeepCurrent(current: String?, incomingRemote: String?): Boolean {
        if (current.isNullOrBlank() || incomingRemote.isNullOrBlank()) return false
        if (stripQueryAndFragment(current) == stripQueryAndFragment(incomingRemote)) return true
        val incomingKey = sourceKey(incomingRemote)
        if (incomingKey.isNotEmpty() && sourceKey(current) == incomingKey) return true
        return localSourceKey(current) == incomingKey
    }

    fun scaledSize(
        width: Int,
        height: Int,
        maxWidth: Int = MAX_WIDTH,
        maxHeight: Int = MAX_HEIGHT
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val scale = minOf(1f, maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        return (width * scale).toInt().coerceAtLeast(1) to
            (height * scale).toInt().coerceAtLeast(1)
    }

    fun shouldRecompress(width: Int, height: Int, alreadyJpeg: Boolean): Boolean {
        if (!alreadyJpeg) return true
        return width > MAX_WIDTH || height > MAX_HEIGHT
    }

    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    fun shouldAdoptLocal(currentUri: String?, localUri: String?): Boolean {
        if (localUri.isNullOrBlank()) return false
        if (!needsLocalCopy(currentUri)) return false
        return currentUri != localUri
    }

    fun decodeSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int = MAX_WIDTH,
        maxHeight: Int = MAX_HEIGHT
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxWidth * 2 || height / sample > maxHeight * 2) {
            sample *= 2
        }
        return sample
    }

    fun collectPending(
        cards: List<Pair<String, String?>>,
        presets: List<Pair<String, String?>>
    ): List<Pair<ThumbnailBindPolicy.Target, String>> {
        val result = mutableListOf<Pair<ThumbnailBindPolicy.Target, String>>()
        cards.forEach { (id, uri) ->
            if (needsLocalCopy(uri) && !uri.isNullOrBlank()) {
                result.add(ThumbnailBindPolicy.Target.card(id) to uri)
            }
        }
        presets.forEach { (id, uri) ->
            if (needsLocalCopy(uri) && !uri.isNullOrBlank()) {
                result.add(ThumbnailBindPolicy.Target.preset(id) to uri)
            }
        }
        return result
    }

    private fun safeToken(value: String): String =
        value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "x" }.take(80)

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
