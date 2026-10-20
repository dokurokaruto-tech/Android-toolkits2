package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 「私の図書館」に置く、題名と本文を持つメールボックス形式の記録。 */
data class LibraryNote(
    val id: String,
    val kind: LibraryNoteKind,
    val title: String,
    val body: String
)

enum class LibraryNoteKind(
    val storageDirectory: String,
    val driveKey: String,
    val displayName: String
) {
    ESSAY("essays", "essay", "エッセイ"),
    WISH_LIST("wish_list", "wish", "やりたい事リスト");

    companion object {
        fun fromDriveKey(key: String?): LibraryNoteKind? =
            entries.firstOrNull { it.driveKey == key }
    }
}

/** JSON の変換だけを担当する。ファイルI/Oは LibraryNoteStore に閉じる。 */
object LibraryNoteJson {

    fun encode(note: LibraryNote): String = JSONObject()
        .put("id", note.id)
        .put("kind", note.kind.driveKey)
        .put("title", note.title)
        .put("body", note.body)
        .toString()

    fun decode(kind: LibraryNoteKind, id: String, raw: String?): LibraryNote? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return try {
            val json = JSONObject(raw)
            LibraryNote(
                id = id,
                kind = kind,
                title = json.optString("title", ""),
                body = json.optString("body", "")
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** 日記ファイルとは別の場所に保存し、既存の日付JSONとの互換性を保つ。 */
object LibraryNoteStore {

    private const val ROOT_DIRECTORY = "my_library"
    private const val FILE_SUFFIX = ".json"
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")

    data class Address(val kind: LibraryNoteKind, val id: String)

    fun newId(): String = UUID.randomUUID().toString()

    fun notes(context: Context, kind: LibraryNoteKind): List<LibraryNote> =
        directory(context, kind).listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file ->
                val id = file.name.removeSuffix(FILE_SUFFIX)
                if (!safeId.matches(id)) null else load(context, kind, id)?.takeIf { it.title.isNotBlank() }
            }
            .orEmpty()

    fun load(context: Context, kind: LibraryNoteKind, id: String): LibraryNote? {
        if (!safeId.matches(id)) {
            return null
        }
        return LibraryNoteJson.decode(kind, id, AtomicFiles.readUtf8(noteFile(context, kind, id)))
    }

    fun save(context: Context, note: LibraryNote) {
        if (!safeId.matches(note.id)) {
            return
        }
        if (note.title.isBlank() && note.body.isBlank()) {
            noteFile(context, note.kind, note.id).delete()
            return
        }
        AtomicFiles.writeUtf8(noteFile(context, note.kind, note.id), LibraryNoteJson.encode(note))
    }

    fun delete(context: Context, kind: LibraryNoteKind, id: String) {
        if (safeId.matches(id)) {
            noteFile(context, kind, id).delete()
        }
    }

    fun savedIds(context: Context, kind: LibraryNoteKind): List<String> =
        directory(context, kind).listFiles()
            ?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }
            ?.map { it.name.removeSuffix(FILE_SUFFIX) }
            ?.filter { safeId.matches(it) }
            .orEmpty()

    fun readRaw(context: Context, kind: LibraryNoteKind, id: String): String? =
        if (safeId.matches(id)) AtomicFiles.readUtf8(noteFile(context, kind, id)) else null

    fun writeRaw(context: Context, kind: LibraryNoteKind, id: String, raw: String) {
        if (safeId.matches(id)) {
            AtomicFiles.writeUtf8(noteFile(context, kind, id), raw)
        }
    }

    fun modifiedMs(context: Context, kind: LibraryNoteKind, id: String): Long =
        if (!safeId.matches(id)) 0L else noteFile(context, kind, id).let { if (it.isFile) it.lastModified() else 0L }

    fun driveFileName(kind: LibraryNoteKind, id: String): String {
        require(safeId.matches(id)) { "Invalid library note id" }
        return "library_${kind.driveKey}_$id$FILE_SUFFIX"
    }

    fun addressOf(driveFileName: String): Address? {
        if (!driveFileName.endsWith(FILE_SUFFIX)) {
            return null
        }
        val base = driveFileName.removeSuffix(FILE_SUFFIX)
        val kind = LibraryNoteKind.entries.firstOrNull { base.startsWith("library_${it.driveKey}_") }
            ?: return null
        val id = base.removePrefix("library_${kind.driveKey}_")
        return id.takeIf { safeId.matches(it) }?.let { Address(kind, it) }
    }

    private fun directory(context: Context, kind: LibraryNoteKind): File =
        File(File(context.filesDir, ROOT_DIRECTORY), kind.storageDirectory)

    private fun noteFile(context: Context, kind: LibraryNoteKind, id: String): File =
        File(directory(context, kind), "$id$FILE_SUFFIX")
}
