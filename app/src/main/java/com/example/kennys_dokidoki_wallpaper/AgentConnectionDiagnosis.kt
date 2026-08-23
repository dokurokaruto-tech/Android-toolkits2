package com.example.kennys_dokidoki_wallpaper

/**
 * PC生成エージェントへの接続失敗を、原因コードと次の確認手順へ落とす。
 */
data class AgentConnectionDiagnosis(
    val code: String,
    val title: String,
    val reason: String,
    val nextStep: String,
    val raw: String = "",
    val target: String = "",
    val path: String = ""
) {
    fun displayText(): String = buildString {
        appendLine("[$code] $title")
        if (target.isNotBlank()) appendLine("接続先: $target")
        if (path.isNotBlank()) appendLine("経路: $path")
        appendLine("原因: $reason")
        appendLine("確認: $nextStep")
        if (raw.isNotBlank()) appendLine("詳細: $raw")
    }.trimEnd()
}

object AgentConnectionClassifier {
    const val OK = "OK"
    const val URL_UNSET = "URL_UNSET"
    const val TIMEOUT = "TIMEOUT"
    const val CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
    const val REFUSED = "REFUSED"
    const val UNREACHABLE = "UNREACHABLE"
    const val DNS = "DNS"
    const val AUTH = "AUTH"
    const val HTTP = "HTTP"
    const val WRONG_SERVICE = "WRONG_SERVICE"
    const val SD_DOWN = "SD_DOWN"
    const val UNKNOWN = "UNKNOWN"

    fun fromException(
        error: Throwable,
        target: String = "",
        path: String = ""
    ): AgentConnectionDiagnosis {
        val raw = collectRaw(error)
        val text = raw.lowercase()
        return when {
            text.contains("urlが未設定") || text.contains("url is blank") -> diagnosis(
                URL_UNSET, "接続先URLが未設定",
                "AndroidにPC生成エージェントのURLが入っていない。",
                "設定 → PC生成エージェントの接続設定で、PC画面の ANDROID APP URL を保存する。",
                raw, target, path
            )
            isConnectTimeout(error, text) -> diagnosis(
                CONNECT_TIMEOUT, "接続そのものが時間内に完了しない",
                "HTTP以前。相手のポートまでTCPが届いていない（LAN切断・スリープ・ファイアウォール）。",
                "同じWi-Fiか、PCがスリープしていないか、start-agent.bat が起動中かを見る。予備URLに Tailscale の 100.x を入れておく。",
                raw, target, path
            )
            isTimeout(error, text) -> diagnosis(
                TIMEOUT, "応答が時間内に返ってこない",
                "TCPは出せたが、相手が制限時間内に返事しなかった（WinError 10060 と同じ系統）。",
                "同じWi-Fiか、PCがスリープしていないか、start-agent.bat が起動中か、Windowsファイアウォールで3001が許可されているかを見る。",
                raw, target, path
            )
            isRefused(error, text) -> diagnosis(
                REFUSED, "接続を拒否された",
                "そのIPのそのポートで待っているプログラムがいない。",
                "PCで start-agent.bat を起動し、表示された ANDROID APP URL とポート（通常3001）がAndroidの設定と一致するか確認する。",
                raw, target, path
            )
            isDns(error, text) -> diagnosis(
                DNS, "ホスト名を解決できない",
                "書いたホスト名がDNSで見つからない。",
                "127.0.0.1 は使わず、PC起動画面の IP（例: 192.168.x.x）をそのまま入れる。",
                raw, target, path
            )
            isUnreachable(error, text) -> diagnosis(
                UNREACHABLE, "ネットワーク的に届かない",
                "端末からそのIPへパケットが届いていない。",
                "スマホとPCが同じLAN/Tailscaleか、VPNの食い違いか、PCのIPが変わっていないかを見る。",
                raw, target, path
            )
            text.contains("http 401") || text.contains("authentication required") -> diagnosis(
                AUTH, "認証に失敗した",
                "エージェント側の api_key とAndroidのAPIキーが一致していない。",
                "config.json の api_key と、アプリの接続設定のAPIキーを同じにする。空なら両方空にする。",
                raw, target, path
            )
            httpStatus(text) != null -> {
                val status = httpStatus(text)
                diagnosis(
                    HTTP, "エージェントは応答したがHTTP $status",
                    "接続自体はできている。相手がエラーを返した。",
                    if (status in 500..599) "PC側のエージェント／SDのログを見る。502ならSD WebUIが落ちていることが多い。"
                    else "返ってきた本文と、URLのパスが /api/v1/health など正しいかを見る。",
                    raw, target, path
                )
            }
            else -> diagnosis(
                UNKNOWN, "分類できない接続失敗",
                "例外は出たが、既知のタイムアウト／拒否／不通には当てはまらない。",
                "下の詳細をコピーして残す。接続先IPと、失敗した経路も一緒に見る。",
                raw, target, path
            )
        }
    }

    fun fromHealth(
        service: String,
        sdReachable: Boolean?,
        target: String
    ): AgentConnectionDiagnosis {
        if (service != "android-toolkits-generation-agent") {
            return diagnosis(
                WRONG_SERVICE, "別のサーバーに当たっている",
                "応答は返ったが、PC生成エージェントではなかった（service=$service）。",
                "ANDROID APP URL をエージェント起動画面の値に直す。SDの7860や別アプリのURLを入れていないか確認する。",
                "service=$service", target, "/api/v1/health"
            )
        }
        if (sdReachable == false) {
            return diagnosis(
                SD_DOWN, "エージェントには届いたがSDが応答しない",
                "PC生成エージェントは生きている。同じPCの Stable Diffusion WebUI / Forge API に届いていない。",
                "SDを起動し、--api が有効か、config.json の sd_base_url（通常 http://127.0.0.1:7860）を確認する。",
                "sd_reachable=false", target, "/api/v1/health"
            )
        }
        return diagnosis(
            OK, "接続成功",
            "PC生成エージェントに届いた。",
            if (sdReachable == true) "SD APIにも届いている。" else "SD到達は未確認。生成時に失敗するならSDを起動する。",
            "service=$service sd_reachable=$sdReachable", target, "/api/v1/health"
        )
    }

    fun redactUrl(url: String): String {
        if (url.isBlank()) return "(未設定)"
        return url.replace(Regex("([?&]token=)[^&]*"), "$1***")
    }

    private fun diagnosis(
        code: String,
        title: String,
        reason: String,
        nextStep: String,
        raw: String,
        target: String,
        path: String
    ) = AgentConnectionDiagnosis(code, title, reason, nextStep, raw, target, path)

    private fun collectRaw(error: Throwable): String {
        val parts = linkedSetOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            val name = current.javaClass.simpleName
            val message = current.message?.trim().orEmpty()
            parts.add(if (message.isBlank()) name else "$name: $message")
            current = current.cause
            depth++
        }
        return parts.joinToString(" | ")
    }

    private fun isConnectTimeout(error: Throwable, text: String): Boolean {
        if (!isTimeout(error, text)) return false
        return text.contains("failed to connect") ||
            text.contains("failed to connect to") ||
            (text.contains("connect") && text.contains("after") && text.contains("ms"))
    }

    private fun isTimeout(error: Throwable, text: String): Boolean {
        if (nameContains(error, "SocketTimeout")) return true
        return listOf("timeout", "timed out", "etimedout", "10060").any { it in text }
    }

    fun shouldTryNextEndpoint(code: String): Boolean =
        code == CONNECT_TIMEOUT || code == UNREACHABLE || code == REFUSED || code == TIMEOUT || code == DNS

    private fun isRefused(error: Throwable, text: String): Boolean {
        if (nameContains(error, "ConnectException") && !isTimeout(error, text)) return true
        return listOf("connection refused", "econnrefused", "接続を拒否").any { it in text }
    }

    private fun isDns(error: Throwable, text: String): Boolean {
        if (nameContains(error, "UnknownHost")) return true
        return listOf("unable to resolve", "unknown host", "no address associated").any { it in text }
    }

    private fun isUnreachable(error: Throwable, text: String): Boolean {
        if (nameContains(error, "NoRouteToHost")) return true
        return listOf("network is unreachable", "enetunreach", "no route to host", "enonet").any { it in text }
    }

    private fun nameContains(error: Throwable, fragment: String): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.javaClass.simpleName.contains(fragment, ignoreCase = true)) return true
            current = current.cause
        }
        return false
    }

    private fun httpStatus(text: String): Int? {
        val match = Regex("http (\\d{3})").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
