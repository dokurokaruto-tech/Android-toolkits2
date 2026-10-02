package com.example.kennys_dokidoki_wallpaper

object GeneratedSavedPolicy {
    fun isSaved(uri: String, savedKeys: Set<String>): Boolean {
        val ref = GeneratedImageIdentity.remoteRef(uri) ?: return false
        return GeneratedImageIdentity.key(ref) in savedKeys
    }
}
