package com.example.kennys_dokidoki_wallpaper

/** Pure selection rules for persona-linked Suggest profiles. */
internal object ChatSuggestProfileSelectionPolicy {
    /**
     * Resolve the active profile only for an actual persona transition. Re-opening settings or
     * re-selecting the same persona must preserve the user's explicitly selected profile.
     */
    fun profileIdForPersonaChange(
        profiles: List<ChatSuggestProfile>,
        activeProfileId: String?,
        previousPersonaId: String?,
        newPersonaId: String?
    ): String? {
        val current = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
        if (previousPersonaId == newPersonaId) {
            return current?.id
        }

        val matching = if (newPersonaId.isNullOrBlank()) {
            if (current?.personaId == null) {
                current
            } else {
                profiles.firstOrNull { it.personaId == null }
            }
        } else {
            current?.takeIf { it.personaId == newPersonaId }
                ?: profiles.firstOrNull { it.personaId == newPersonaId }
                ?: current?.takeIf { it.personaId == null }
                ?: profiles.firstOrNull { it.personaId == null }
        }
        return matching?.id
    }
}
