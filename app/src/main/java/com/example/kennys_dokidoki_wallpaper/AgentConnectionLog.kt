package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 接続の成功／失敗を端末に残し、設定画面からコピーできるようにする。
 */
object AgentConnectionLog {
    private const val PREFS = "settings"
    private const val KEY = "agent_connection_log"
    private const val LIMIT = 30
    private val lock = Any()

    @Volatile
    var last: AgentConnectionDiagnosis? = null
        private set

    fun record(context: Context, action: String, diagnosis: AgentConnectionDiagnosis) {
        last = diagnosis
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val item = JSONObject().apply {
            put("at", stamp)
            put("action", action)
            put("code", diagnosis.code)
            put("title", diagnosis.title)
            put("reason", diagnosis.reason)
            put("nextStep", diagnosis.nextStep)
            put("target", diagnosis.target)
            put("path", diagnosis.path)
            put("raw", diagnosis.raw)
        }
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val array = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
            val next = JSONArray()
            next.put(item)
            for (index in 0 until array.length()) {
                if (next.length() >= LIMIT) break
                next.put(array.getJSONObject(index))
            }
            prefs.edit().putString(KEY, next.toString()).apply()
        }
    }

    fun formatAll(context: Context): String {
        val current = last
        val persisted = synchronized(lock) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        }
        val array = runCatching { JSONArray(persisted) }.getOrDefault(JSONArray())
        if (array.length() == 0 && current == null) return "まだ接続ログはない。"
        return buildString {
            appendLine("PC生成エージェント接続ログ")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                appendLine("----")
                appendLine("${item.optString("at")}  ${item.optString("action")}  [${item.optString("code")}] ${item.optString("title")}")
                val target = item.optString("target")
                if (target.isNotBlank()) appendLine("接続先: $target")
                val path = item.optString("path")
                if (path.isNotBlank()) appendLine("経路: $path")
                appendLine("原因: ${item.optString("reason")}")
                appendLine("確認: ${item.optString("nextStep")}")
                val raw = item.optString("raw")
                if (raw.isNotBlank()) appendLine("詳細: $raw")
            }
        }.trimEnd()
    }
}
