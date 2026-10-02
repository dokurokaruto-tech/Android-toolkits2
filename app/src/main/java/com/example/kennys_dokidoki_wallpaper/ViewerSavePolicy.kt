package com.example.kennys_dokidoki_wallpaper

object ViewerSavePolicy {
    enum class State { HIDDEN, CHECKING, READY, SAVING, WAITING, SAVED }

    fun state(uri: String?, savedKeys: Set<String>?, savingUri: String?): State {
        if (uri.isNullOrBlank()) {
            return State.HIDDEN
        }
        val key = GeneratedImageIdentity.canonicalKey(uri)
        if (savingUri != null && key == GeneratedImageIdentity.canonicalKey(savingUri)) {
            return State.SAVING
        }
        if (savedKeys == null) {
            return State.CHECKING
        }
        if (key in savedKeys) {
            return State.SAVED
        }
        if (GeneratedImageIdentity.remoteRef(uri) == null) {
            return State.HIDDEN
        }
        return if (savingUri != null) State.WAITING else State.READY
    }
}
