package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri

/**
 * まだカード／プリセット本体が無いときでも、完成したサムネURIを残す。
 */
object ThumbnailBindStore {
    private const val PREFS = "thumbnail_bind_prefs"
    private const val KEY_PENDING = "pending_uris"
    private const val KEY_SOURCES = "source_urls"

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

    /**
     * 端末に保存するとき元のPC URLを覚えておく。
     * ローカルファイルが消えたとき、ハッシュだけでは逆引きできないので
     * この記録が再取得の頼みになる。
     */
    fun rememberSource(context: Context, target: ThumbnailBindPolicy.Target, remoteUrl: String) {
        if (!target.isValid) return
        val url = remoteUrl.trim()
        if (!ThumbnailLocalCachePolicy.isRemote(url)) return
        val key = ThumbnailBindPolicy.pendingKey(target)
        val sources = ThumbnailBindPolicy.decodePending(prefs(context).getString(KEY_SOURCES, null)).toMutableMap()
        if (sources[key] == url) return
        sources[key] = url
        prefs(context).edit()
            .putString(KEY_SOURCES, ThumbnailBindPolicy.encodePending(sources))
            .commit()
    }

    /** 覚えていた元URL。無い、またはリモートでないなら null。 */
    fun sourceFor(context: Context, target: ThumbnailBindPolicy.Target): String? {
        if (!target.isValid) return null
        val url = ThumbnailBindPolicy.decodePending(prefs(context).getString(KEY_SOURCES, null))[
            ThumbnailBindPolicy.pendingKey(target)
        ]
        return url?.takeIf { ThumbnailLocalCachePolicy.isRemote(it) }
    }
}
