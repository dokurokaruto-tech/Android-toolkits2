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
    fun driveFileNamesKeepLibraryNotesSeparateFromDiaryDates() {
        val essayName = LibraryNoteStore.driveFileName(LibraryNoteKind.ESSAY, "note-123")
        val wishName = LibraryNoteStore.driveFileName(LibraryNoteKind.WISH_LIST, "note-123")

        assertEquals(LibraryNoteStore.Address(LibraryNoteKind.ESSAY, "note-123"), LibraryNoteStore.addressOf(essayName))
        assertEquals(LibraryNoteStore.Address(LibraryNoteKind.WISH_LIST, "note-123"), LibraryNoteStore.addressOf(wishName))
        assertNull(LibraryNoteStore.addressOf("library_essay_../secret.json"))
    }
}
