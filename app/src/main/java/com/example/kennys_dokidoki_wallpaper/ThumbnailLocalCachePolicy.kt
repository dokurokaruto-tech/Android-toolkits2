package com.example.kennys_dokidoki_wallpaper

import java.io.File
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

    /** 閲覧（PC完成画像グリッド）の端末キャッシュ。カード／プリセット同様に予算管理する。 */
    const val KIND_LIBRARY = "lib"

    private val REMOTE_FILE = Regex(
        """/api/v1/(?:files|thumbnail-files|mobile-thumbnails)/([^/?#]+)/([^/?#]+)"""
    )
    private val DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val MANAGED_NAME = Regex("""^(?:card|preset|lib)_[A-Za-z0-9._-]+_[0-9a-f]+\.jpg$""")

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
        return Regex("""^(?:card|preset|lib)_[A-Za-z0-9._-]+_([0-9a-f]+)\.jpg$""")
            .find(name)
            ?.groupValues
            ?.get(1)
    }

    fun localFileName(kind: String, id: String, sourceUrl: String): String =
        "${safeToken(kind)}_${safeToken(id)}_${sourceKey(sourceUrl)}.jpg"

    fun libraryFileName(remoteUrl: String): String? {
        val key = sourceKey(remoteUrl)
        if (key.isEmpty()) return null
        return "${KIND_LIBRARY}_lib_${key}.jpg"
    }

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

    /**
     * 消えたローカルサムネイルの回復判定。
     * file:// で実ファイルが無いときだけ true。リモートURIは enqueuePending が、
     * 空はそもそも対象外が扱う。
     */
    fun needsRecovery(uri: String?): Boolean {
        if (uri.isNullOrBlank() || isRemote(uri)) return false
        if (!uri.startsWith("file://")) return false
        return !File(uri.removePrefix("file://")).exists()
    }

    /** 先頭バイトが保存形式（JPEG / PNG / WebP）と一致するか。 */
    fun looksLikeImage(head: ByteArray): Boolean = when {
        head.size >= 3 &&
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> true
        head.size >= 4 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> true
        head.size >= 12 &&
            head[0] == 0x52.toByte() && head[8] == 0x57.toByte() &&
            head[9] == 0x45.toByte() && head[10] == 0x42.toByte() && head[11] == 0x50.toByte() -> true
        else -> false
    }

    /**
     * file:// の差し先が実際に画像として読めるか。
     * 消えた・空・壊れたファイルは「!」のままなので、
     * 一括生成では未設定として扱う。リモートなど判定外は壊れていない扱い。
     */
    fun isBrokenLocalImage(uri: String?): Boolean {
        if (uri.isNullOrBlank() || isRemote(uri) || !uri.startsWith("file://")) return false
        val file = File(uri.removePrefix("file://"))
        if (!file.isFile || file.length() <= 0L) return true
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return true
        return !looksLikeImage(head.copyOf(read))
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

    data class DiskFile(val name: String, val bytes: Long, val lastModified: Long)

    fun keepPrefixes(cardIds: Collection<String>, presetIds: Collection<String>): Set<String> {
        return buildSet {
            cardIds.forEach { add(managedPrefix("card", it)) }
            presetIds.forEach { add(managedPrefix("preset", it)) }
        }
    }

    fun filesToDelete(
        files: List<DiskFile>,
        keepPrefixes: Set<String>,
        maxBytes: Long = ImageMemoryPressurePolicy.THUMB_DISK_MAX_BYTES
    ): List<String> {
        val managed = files.filter { isManagedFileName(it.name) }
        // 閲覧キャッシュ（lib）はカード/プリセットに紐づかないので孤児扱いしない。
        // 容量予算だけに従い、古いものから落ちる。
        val (library, bound) = managed.partition { it.name.startsWith("${KIND_LIBRARY}_") }
        val orphans = if (keepPrefixes.isEmpty()) {
            emptyList()
        } else {
            bound.filter { file -> keepPrefixes.none { file.name.startsWith(it) } }
        }
        val keepers = (library + if (keepPrefixes.isEmpty()) {
            bound
        } else {
            bound.filter { file -> keepPrefixes.any { file.name.startsWith(it) } }
        })
            .sortedBy { it.lastModified }
            .toMutableList()
        var total = keepers.fold(0L) { acc, file -> acc + file.bytes }
        val overflow = mutableListOf<String>()
        val budget = maxBytes.coerceAtLeast(0L)
        while (keepers.size > 1 && total > budget) {
            val dropped = keepers.removeAt(0)
            total -= dropped.bytes
            overflow += dropped.name
        }
        return orphans.map { it.name } + overflow
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
