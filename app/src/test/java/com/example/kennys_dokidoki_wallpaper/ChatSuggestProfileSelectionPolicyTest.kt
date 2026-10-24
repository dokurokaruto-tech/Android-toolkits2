package com.example.kennys_dokidoki_wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSuggestProfileSelectionPolicyTest {
    private val personaProfile = ChatSuggestProfile(
        id = "linked-profile",
        name = "ペルソナ用",
        personaId = "persona-a"
    )
    private val manualProfile = ChatSuggestProfile(
        id = "manual-profile",
        name = "手動選択",
        personaId = null
    )
    private val profiles = listOf(personaProfile, manualProfile)

    @Test
    fun reopeningSettingsWithTheSamePersonaKeepsTheManuallySelectedProfile() {
        val selectedId = ChatSuggestProfileSelectionPolicy.profileIdForPersonaChange(
            profiles = profiles,
            activeProfileId = manualProfile.id,
            previousPersonaId = "persona-a",
            newPersonaId = "persona-a"
        )

        assertEquals(manualProfile.id, selectedId)
    }

    @Test
    fun actualPersonaChangeStillActivatesTheLinkedProfile() {
        val selectedId = ChatSuggestProfileSelectionPolicy.profileIdForPersonaChange(
            profiles = profiles,
            activeProfileId = manualProfile.id,
            previousPersonaId = "persona-b",
            newPersonaId = "persona-a"
        )

        assertEquals(personaProfile.id, selectedId)
    }

    @Test
    fun clearingPersonaSelectsAnUnlinkedProfileOnlyAfterARealChange() {
        val selectedId = ChatSuggestProfileSelectionPolicy.profileIdForPersonaChange(
            profiles = profiles,
            activeProfileId = personaProfile.id,
            previousPersonaId = "persona-a",
            newPersonaId = null
        )

        assertEquals(manualProfile.id, selectedId)
    }
}
