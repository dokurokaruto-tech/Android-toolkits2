package com.example.kennys_dokidoki_wallpaper

/**
 * LANの一瞬切れを「生成失敗」にしないための判定。PC側のキューは生きたまま。
 */
object AgentNetworkPolicy {
    fun isTransient(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (matches(current)) return true
            current = current.cause
        }
        return false
    }

    fun backoffMs(failureCount: Int): Long {
        val steps = failureCount.coerceAtLeast(1)
        return (1_000L * steps * steps).coerceAtMost(15_000L)
    }

    fun shouldAbortMonitor(error: Throwable, consecutiveFailures: Int): Boolean {
        if (isTransient(error)) return false
        return consecutiveFailures >= 8
    }

    private fun matches(error: Throwable): Boolean {
        val name = error.javaClass.simpleName
        if (name.contains("SocketTimeout", ignoreCase = true) ||
            name.contains("ConnectException", ignoreCase = true) ||
            name.contains("UnknownHost", ignoreCase = true) ||
            name.contains("NoRouteToHost", ignoreCase = true) ||
            name.contains("SocketException", ignoreCase = true)
        ) {
            return true
        }
        val message = error.message.orEmpty()
        val markers = listOf(
            "timeout",
            "timed out",
            "failed to connect",
            "unable to resolve",
            "connection reset",
            "connection refused",
            "broken pipe",
            "network is unreachable",
            "software caused connection abort",
            "econnreset",
            "etimedout",
            "10060",
            "10054"
        )
        return markers.any { message.contains(it, ignoreCase = true) }
    }
}
