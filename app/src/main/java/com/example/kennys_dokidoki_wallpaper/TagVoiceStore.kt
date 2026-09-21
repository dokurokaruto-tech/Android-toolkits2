package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/** Immutable samples keep old replies independent of later tag edits. */
object TagVoiceStore {
    private const val DIRECTORY = "tag_voices"
    private val sampleIdPattern = Regex("[0-9a-f]{64}")

    fun importSample(context: Context, uri: Uri): TagVoice {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readBytesLimited(ChatVoicePolicy.MAX_SAMPLE_BYTES)
        } ?: error("音声ファイルを開けません。")
        require(bytes.isNotEmpty()) { "音声ファイルが空です。" }
        val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) { it.getString(0) } else { null }
        } ?: "サンプル音声"
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, id)
        if (!file.exists()) {
            val temporary = File.createTempFile("sample-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(file)) { "音声ファイルを保存できません。" }
            } finally {
                temporary.delete()
            }
        }
        return TagVoice(id, name)
    }

    fun readSample(context: Context, voice: TagVoice): ByteArray {
        require(sampleIdPattern.matches(voice.sampleId)) { "音声ファイルのIDが不正です。" }
        val file = File(File(context.filesDir, DIRECTORY), voice.sampleId)
        check(file.isFile) { "サンプル音声が見つかりません。タグで音声を選び直して返信を再生成してください。" }
        return file.inputStream().use { it.readBytesLimited(ChatVoicePolicy.MAX_SAMPLE_BYTES) }
    }
}

internal fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) { break }
        require(output.size() + count <= limit) { "ファイルがサイズ制限を超えています。" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
