package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryNoteTest {

    @Test
    fun essayAndWishListRoundTripTheirTitleAndBody() {
        listOf(LibraryNoteKind.ESSAY, LibraryNoteKind.WISH_LIST).forEach { kind ->
            val note = LibraryNote(
                id = "note-123",
                kind = kind,
                title = "題名",
                body = "本文の1行目\n2行目"
            )

            assertEquals(note, LibraryNoteJson.decode(kind, note.id, LibraryNoteJson.encode(note)))
        }
    }

    @Test
    fun malformedNoteJsonIsIgnored() {
        assertNull(LibraryNoteJson.decode(LibraryNoteKind.ESSAY, "bad-id", "{bad json"))
    }

    @Test
    fun backupPathsKeepTheTwoLibraryKindsSeparateAndRejectTraversal() {
        assertEquals(
            LibraryBackupPolicy.NoteAddress(LibraryNoteKind.ESSAY, "note-123"),
            LibraryBackupPolicy.noteAddressOf("notes/essay/note-123.json")
        )
        assertEquals(
            LibraryBackupPolicy.NoteAddress(LibraryNoteKind.WISH_LIST, "note-123"),
            LibraryBackupPolicy.noteAddressOf("notes/wish/note-123.json")
        )
        assertNull(LibraryBackupPolicy.noteAddressOf("notes/essay/../secret.json"))
        assertNull(LibraryBackupPolicy.noteAddressOf("notes/unknown/note-123.json"))
    }
}
