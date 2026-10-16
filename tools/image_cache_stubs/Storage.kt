package com.example.kennys_dokidoki_wallpaper
object ImageStoragePolicy {
    fun isRemote(uri: android.net.Uri): Boolean = uri.toString().startsWith("http")
}
