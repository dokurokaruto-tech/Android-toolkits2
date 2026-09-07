package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders

/**
 * PC 生成エージェントの画像を Glide で読むときの認証。
 *
 * 旧: GenerationAgentClient.absoluteUrl() が "?token=<APIキー>" をクエリに付けていた。
 *     → Glide のディスクキャッシュのキー、logcat、プロキシ/サーバのアクセスログ、
 *       スクリーンショットの URL 表示すべてにキーが残る。
 * 新: Authorization ヘッダで送る。キャッシュキーは URL のみ。
 *
 *   Glide.with(view).load(AgentGlideUrl.of(context, imageUrl)).into(view)
 *
 * GenerationAgentClient.absoluteUrl() からは token 付与コードを削除し、
 * サーバ側 (pc-generation-agent) は既に Bearer ヘッダを受け付けている。
 */
object AgentGlideUrl {
    private const val HEADER_AUTHORIZATION = "Authorization"
    private const val BEARER_PREFIX = "Bearer "
    private val tokenQuery = Regex("([?&])token=[^&]*&?")

    fun of(context: Context, url: String): GlideUrl {
        val key = AppSecrets.agentApiKey(context)
        val headers = LazyHeaders.Builder().apply {
            if (!key.isNullOrBlank()) {
                addHeader(HEADER_AUTHORIZATION, BEARER_PREFIX + key)
            }
        }.build()
        return GlideUrl(stripToken(url), headers)
    }

    /** 旧形式で保存された URL から token クエリを取り除く */
    fun stripToken(url: String): String {
        val stripped = url.replace(tokenQuery) { match -> match.groupValues[1] }
        return stripped.trimEnd('?', '&')
    }
}
