package com.example.kennys_dokidoki_wallpaper

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/** 完了ファイルだけ公開。RAM解放とは独立した容量制限付きLRU。 */
internal class OriginalDiskCache(private val directory: File, private val budget: Long = DEFAULT_BUDGET) {
    companion object {
        const val DEFAULT_BUDGET = 2L * 1024 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 128L * 1024 * 1024

        fun key(url: String): String {
            val uri = URI(url)
            // PCごとに分離し、認証トークン更新だけでは再取得しない。
            val identity = if (uri.path?.startsWith("/api/v1/files/") == true) {
                "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}"
            } else {
                url.substringBefore('#')
            }
            return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun existing(url: String): File? {
        val file = File(directory, key(url))
        if (!file.isFile || file.length() == 0L) {
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return file
    }

    fun store(url: String, input: InputStream, declared: Long): File {
        require(declared <= MAX_IMAGE_BYTES) { "画像が大きすぎます" }
        directory.mkdirs()
        val temporary = File.createTempFile("download-", ".part", directory)
        try {
            var count = 0L
            temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) {
                        break
                    }
                    count += read
                    if (count > MAX_IMAGE_BYTES) {
                        throw IOException("画像が大きすぎます")
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (count == 0L || (declared >= 0 && declared != count)) {
                throw IOException("画像のダウンロードが途中で終了しました")
            }
            val destination = File(directory, key(url))
            if (!temporary.renameTo(destination)) {
                throw IOException("キャッシュを保存できません")
            }
            prune(destination)
            return destination
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    private fun prune(protected: File) {
        val listed = directory.listFiles().orEmpty()
        val staleBefore = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        listed.filter { it.name.endsWith(".part") && it.lastModified() < staleBefore }.forEach { it.delete() }
        val files = listed.filter { it.isFile && !it.name.endsWith(".part") }
        var bytes = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= budget) {
                break
            }
            if (file == protected) {
                continue
            }
            val size = file.length()
            if (file.delete()) {
                bytes -= size
            }
        }
    }
}
