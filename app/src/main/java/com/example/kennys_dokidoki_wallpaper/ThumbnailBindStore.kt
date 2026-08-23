package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri

/**
 * まだカード／プリセット本体が無いときでも、完成したサムネURIを残す。
 */
object ThumbnailBindStore {
    private const val PREFS = "thumbnail_bind_prefs"
    private const val KEY_PENDING = "pending_uris"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun putPending(context: Context, target: ThumbnailBindPolicy.Target, uri: Uri) {
        if (!target.isValid) return
        val text = uri.toString().trim()
        if (text.isEmpty()) return
        val pending = ThumbnailBindPolicy.decodePending(prefs(context).getString(KEY_PENDING, null)).toMutableMap()
        pending[ThumbnailBindPolicy.pendingKey(target)] = text
        prefs(context).edit()
            .putString(KEY_PENDING, ThumbnailBindPolicy.encodePending(pending))
            .commit()
    }

    fun peekPending(context: Context, target: ThumbnailBindPolicy.Target): Uri? {
        if (!target.isValid) return null
        val text = ThumbnailBindPolicy.decodePending(prefs(context).getString(KEY_PENDING, null))[
            ThumbnailBindPolicy.pendingKey(target)
        ] ?: return null
        return text.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    fun consumePending(context: Context, target: ThumbnailBindPolicy.Target): Uri? {
        if (!target.isValid) return null
        val pending = ThumbnailBindPolicy.decodePending(prefs(context).getString(KEY_PENDING, null)).toMutableMap()
        val text = pending.remove(ThumbnailBindPolicy.pendingKey(target))
        prefs(context).edit()
            .putString(KEY_PENDING, ThumbnailBindPolicy.encodePending(pending))
            .commit()
        return text?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }
}
