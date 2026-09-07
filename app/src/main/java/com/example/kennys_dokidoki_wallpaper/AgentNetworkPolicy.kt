package com.example.kennys_dokidoki_wallpaper

import java.net.URI

/**
 * PC 生成エージェントの接続先 URL を検証する。
 *
 *   https://…                → 常に許可
 *   http://<プライベートIP>   → 許可 (LAN / Tailscale / localhost)
 *   http://<公開ホスト>       → 拒否 (API キーが平文で流れる)
 *
 * 旧コードは入力に "http://" を無条件で前置し、公開ホストにも平文で
 * Authorization: Bearer <key> を送っていた。
 *
 * network_security_config.xml とは独立した実行時の防御。
 */
object AgentNetworkPolicy {

    sealed class Result {
        data class Ok(val normalizedUrl: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private const val SCHEME_HTTP = "http"
    private const val SCHEME_HTTPS = "https"

    /** 入力を正規化して検証する。空文字は Ok("") (未設定) として扱う */
    fun validate(raw: String): Result {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) {
            return Result.Ok("")
        }

        val withScheme = if (trimmed.contains("://")) trimmed else "$SCHEME_HTTP://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return Result.Rejected("URL の形式が不正です: $trimmed")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
            ?: return Result.Rejected("ホスト名が読み取れません: $trimmed")

        return when (scheme) {
            SCHEME_HTTPS -> Result.Ok(withScheme)
            SCHEME_HTTP -> if (isPrivateHost(host)) {
                Result.Ok(withScheme)
            } else {
                Result.Rejected(
                    "http:// はプライベートネットワーク (192.168.x / 10.x / 100.64-127.x / localhost) 専用です。" +
                        "公開ホストには https:// を使ってください: $host"
                )
            }
            else -> Result.Rejected("対応していないスキームです: $scheme")
        }
    }

    /** RFC1918 / CGNAT(Tailscale) / ループバック / リンクローカル / mDNS */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".localdomain")) {
            return true
        }
        if (h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) {
            return true
        }

        val octets = parseIpv4(h) ?: return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 127 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 100 && b in 64..127 -> true // Tailscale CGNAT 100.64.0.0/10
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) {
            return null
        }
        val octets = IntArray(4)
        for (i in parts.indices) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) {
                return null
            }
            octets[i] = value
        }
        return octets
    }
}
