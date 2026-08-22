package com.example.kennys_dokidoki_wallpaper

import android.net.Uri
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * Remote PC images are view-only until the user explicitly imports them.
 * Memory caching is allowed for smooth scrolling, but no encoded/decoded image is
 * persisted in Glide's disk cache.
 */
object ImageStoragePolicy {
    fun isRemote(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    fun glideDiskCache(uri: Uri?, localStrategy: DiskCacheStrategy = DiskCacheStrategy.ALL): DiskCacheStrategy =
        if (isRemote(uri)) DiskCacheStrategy.NONE else localStrategy
}
