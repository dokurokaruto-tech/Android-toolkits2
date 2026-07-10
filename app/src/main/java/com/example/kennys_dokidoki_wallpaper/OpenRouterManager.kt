package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

object OpenRouterManager {
    private const val PREFS_NAME = "openrouter_prefs"
    private const val KEY_API_KEYS = "api_keys_v2" // JSONArray of { "key": "...", "label": "..." }
    private const val OLD_KEY_API_KEYS = "api_keys" // Old JSONArray of strings
    private const val KEY_USAGE_DATA = "usage_data" // JSONObject: { "api_key": count }
    private const val KEY_LAST_RESET_TIME = "last_reset_time"
    private const val KEY_MANUAL_SELECTED_KEY = "manual_selected_key"

    private val jstTimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    data class ApiKeyEntry(val key: String, val label: String)

    fun getApiKeys(context: Context): List<ApiKeyEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonV2 = prefs.getString(KEY_API_KEYS, null)
        
        if (jsonV2 != null) {
            val array = JSONArray(jsonV2)
            val list = mutableListOf<ApiKeyEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ApiKeyEntry(obj.getString("key"), obj.optString("label", "Key #${i + 1}")))
            }
            return list
        }

        // 移行処理: 旧バージョン (api_keys) または settings からの移行よ！
        val oldJson = prefs.getString(OLD_KEY_API_KEYS, null)
        val migratedList = mutableListOf<ApiKeyEntry>()
        
        if (oldJson != null) {
            val array = JSONArray(oldJson)
            for (i in 0 until array.length()) {
                migratedList.add(ApiKeyEntry(array.getString(i), "Account #${i + 1}"))
            }
        } else {
            val oldPrefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val oldKey = oldPrefs.getString("openrouter_api_key", null)
            if (!oldKey.isNullOrEmpty()) {
                migratedList.add(ApiKeyEntry(oldKey, "Primary Account"))
            }
        }

        if (migratedList.isNotEmpty()) {
            saveApiKeys(context, migratedList)
        }
        return migratedList
    }

    fun saveApiKeys(context: Context, entries: List<ApiKeyEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("key", entry.key)
                put("label", entry.label)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_API_KEYS, array.toString()).apply()
    }

    fun getApiKeysOnly(context: Context): List<String> {
        return getApiKeys(context).map { it.key }
    }

    private fun getUsageData(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_USAGE_DATA, null) ?: return JSONObject()
        return JSONObject(json)
    }

    private fun saveUsageData(context: Context, data: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USAGE_DATA, data.toString()).apply()
    }

    fun getUsageCount(context: Context, apiKey: String): Int {
        checkAndReset(context)
        val data = getUsageData(context)
        return data.optInt(apiKey, 0)
    }

    fun incrementUsage(context: Context, apiKey: String) {
        checkAndReset(context)
        val data = getUsageData(context)
        val current = data.optInt(apiKey, 0)
        data.put(apiKey, current + 1)
        saveUsageData(context, data)
    }

    fun setUsageCount(context: Context, apiKey: String, count: Int) {
        checkAndReset(context)
        val data = getUsageData(context)
        data.put(apiKey, count)
        saveUsageData(context, data)
    }

    fun setManualSelectedKey(context: Context, apiKey: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MANUAL_SELECTED_KEY, apiKey).apply()
    }

    fun getManualSelectedKey(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MANUAL_SELECTED_KEY, null)
    }

    fun getActiveApiKey(context: Context): String? {
        checkAndReset(context)
        val entries = getApiKeys(context)
        if (entries.isEmpty()) return null
        
        // 手動選択されているキーがあれば、それを最優先するわ！
        val manualKey = getManualSelectedKey(context)
        if (manualKey != null && entries.any { it.key == manualKey }) {
            val usage = getUsageCount(context, manualKey)
            if (usage < 50) return manualKey
            // 使い切ってたら手動選択を解除しちゃうわね
            setManualSelectedKey(context, null)
        }

        val data = getUsageData(context)
        for (entry in entries) {
            if (data.optInt(entry.key, 0) < 50) {
                return entry.key
            }
        }
        return entries.last().key 
    }

    fun getTotalUsage(context: Context): Int {
        checkAndReset(context)
        val entries = getApiKeys(context)
        val data = getUsageData(context)
        var total = 0
        for (entry in entries) {
            total += data.optInt(entry.key, 0)
        }
        return total
    }

    /**
     * 日本時間(JST)の毎朝9時にリセットされる仕様に合わせるわよ！
     */
    private fun checkAndReset(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastReset = prefs.getLong(KEY_LAST_RESET_TIME, 0L)
        val now = System.currentTimeMillis()

        val lastResetCal = Calendar.getInstance(jstTimeZone).apply { timeInMillis = lastReset }
        val nowCal = Calendar.getInstance(jstTimeZone).apply { timeInMillis = now }

        // 前回の9:00 AM (JST) を計算するわ
        val last9am = Calendar.getInstance(jstTimeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        // もし今が9時前なら、リセット対象は「昨日の9時」になるわね
        if (nowCal.before(last9am)) {
            last9am.add(Calendar.DAY_OF_MONTH, -1)
        }

        // 最後にリセットしたのが、直近の9:00 AMより前ならリセット実行よ！
        if (lastReset < last9am.timeInMillis) {
            prefs.edit()
                .putString(KEY_USAGE_DATA, JSONObject().toString())
                .putLong(KEY_LAST_RESET_TIME, now)
                .putString(KEY_MANUAL_SELECTED_KEY, null) // リセット時に手動選択もクリアするわ！
                .apply()
        }
    }
}
