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

    /** カード一覧は端末ファイルだけ出す。リモートURLは裏で保存してから載せる。 */
    fun canDisplayWithoutNetwork(uri: Uri?): Boolean = uri != null && !isRemote(uri)

    fun glideDiskCache(uri: Uri?, localStrategy: DiskCacheStrategy = DiskCacheStrategy.ALL): DiskCacheStrategy =
        if (isRemote(uri)) DiskCacheStrategy.NONE else localStrategy
}
