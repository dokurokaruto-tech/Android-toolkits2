package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderExplorerPolicyTest {

    @Test
    fun primaryStorageUsesPrimaryLabel() {
        assertEquals(
            "primary:Android/data/pkg/files/card_thumbnails",
            FolderExplorerPolicy.documentId("/storage/emulated/0/Android/data/pkg/files/card_thumbnails")
        )
    }

    @Test
    fun sdCardUsesVolumeUuidLabel() {
        assertEquals(
            "0000-1111:Android/data/pkg/files/card_thumbnails",
            FolderExplorerPolicy.documentId("/storage/0000-1111/Android/data/pkg/files/card_thumbnails")
        )
    }

    @Test
    fun appPrivateInternalDirIsNotExplorable() {
        assertNull(FolderExplorerPolicy.documentId("/data/user/0/pkg/files/card_thumbnails"))
    }

    @Test
    fun storageRootsWithoutSubPathAreNotExplorable() {
        assertNull(FolderExplorerPolicy.documentId("/storage/emulated/0"))
        assertNull(FolderExplorerPolicy.documentId("/storage/0000-1111"))
        assertNull(FolderExplorerPolicy.documentId("/storage/"))
    }

    @Test
    fun malformedPathsAreRejected() {
        assertNull(FolderExplorerPolicy.documentId(""))
        assertNull(FolderExplorerPolicy.documentId("/storage"))
        assertNull(FolderExplorerPolicy.documentId("relative/path"))
    }
}
