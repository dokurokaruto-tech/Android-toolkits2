package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * OpenRouter の複数 API キーと無料枠 (1 日 50 リクエスト / キー) の消費量を管理する。
 *
 *   キー本体   → SecurePrefs (Keystore で暗号化)
 *   消費量     → openrouter_prefs/usage_data_v2  { sha256(key) の先頭16桁 : 回数 }
 *   手動選択   → openrouter_prefs/manual_selected_key_hash
 *
 * 旧実装の問題:
 *   - キーが平文 JSON で SharedPreferences に保存され、allowBackup=true でクラウドへ同期されていた
 *   - usage_data の JSON キーに API キーそのものを使っていた (ログ/バックアップに二重に漏れる)
 *   - getApiKeys() が呼ばれるたびに JSON をパース。getActiveApiKey() 1 回で Prefs を 6 回読んでいた
 *
 * 公開 API のシグネチャは旧版と同じ。呼び出し側の変更は不要。
 */
object OpenRouterManager {
    private const val TAG = "OpenRouterManager"

    private const val KEY_USAGE_DATA = "usage_data_v2"
    private const val KEY_LAST_RESET_TIME = "last_reset_time"
    private const val KEY_MANUAL_SELECTED = "manual_selected_key_hash"

    private const val LEGACY_KEYS_V2 = "api_keys_v2"
    private const val LEGACY_KEYS_V1 = "api_keys"
    private const val LEGACY_USAGE = "usage_data"
    private const val LEGACY_MANUAL = "manual_selected_key"
    private const val LEGACY_SETTINGS_KEY = "openrouter_api_key"

    private const val JSON_KEY = "key"
    private const val JSON_LABEL = "label"
    private const val HASH_PREFIX_LENGTH = 16

    /** OpenRouter 無料モデルの 1 日あたり上限 (キー単位) */
    const val FREE_TIER_DAILY_LIMIT = 50

    /** 上限は日本時間 09:00 にリセットされる */
    private const val RESET_HOUR_JST = 9
    private val jstTimeZone: TimeZone = TimeZone.getTimeZone("Asia/Tokyo")

    data class ApiKeyEntry(val key: String, val label: String) {
        val hash: String get() = hashOf(key)

        /** 画面表示用。先頭 6 桁と末尾 4 桁以外を伏せる */
        fun masked(): String {
            if (key.length <= 10) {
                return "••••"
            }
            return key.take(6) + "••••" + key.takeLast(4)
        }
    }

    @Volatile
    private var cache: List<ApiKeyEntry>? = null

    // ------------------------------------------------------------------ keys

    fun getApiKeys(context: Context): List<ApiKeyEntry> {
        cache?.let { return it }

        synchronized(this) {
            cache?.let { return it }
            val loaded = loadEntries(context) ?: migrateLegacyKeys(context)
            cache = loaded
            return loaded
        }
    }

    fun saveApiKeys(context: Context, entries: List<ApiKeyEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put(JSON_KEY, entry.key).put(JSON_LABEL, entry.label))
        }
        SecurePrefs.putString(context, PrefKeys.SECRET_OPENROUTER_KEYS, array.toString())
        cache = entries.toList()
    }

    fun getApiKeysOnly(context: Context): List<String> = getApiKeys(context).map { it.key }

    // ----------------------------------------------------------------- usage

    fun getUsageCount(context: Context, apiKey: String): Int {
        val prefs = prefs(context)
        checkAndReset(prefs)
        return usageData(prefs).optInt(hashOf(apiKey), 0)
    }

    fun incrementUsage(context: Context, apiKey: String) {
        val prefs = prefs(context)
        checkAndReset(prefs)
        val data = usageData(prefs)
        val hash = hashOf(apiKey)
        data.put(hash, data.optInt(hash, 0) + 1)
        prefs.edit().putString(KEY_USAGE_DATA, data.toString()).apply()
    }

    fun setUsageCount(context: Context, apiKey: String, count: Int) {
        val prefs = prefs(context)
        checkAndReset(prefs)
        val data = usageData(prefs)
        data.put(hashOf(apiKey), count.coerceAtLeast(0))
        prefs.edit().putString(KEY_USAGE_DATA, data.toString()).apply()
    }

    fun getTotalUsage(context: Context): Int {
        val prefs = prefs(context)
        checkAndReset(prefs)
        val data = usageData(prefs)
        return getApiKeys(context).sumOf { data.optInt(it.hash, 0) }
    }

    fun getTotalQuota(context: Context): Int = getApiKeys(context).size * FREE_TIER_DAILY_LIMIT

    // ------------------------------------------------------- manual selection

    fun setManualSelectedKey(context: Context, apiKey: String?) {
        val editor = prefs(context).edit()
        if (apiKey == null) {
            editor.remove(KEY_MANUAL_SELECTED)
        } else {
            editor.putString(KEY_MANUAL_SELECTED, hashOf(apiKey))
        }
        editor.apply()
    }

    /** 手動選択中のキー本体。旧 API 互換のため文字列を返す */
    fun getManualSelectedKey(context: Context): String? {
        val hash = prefs(context).getString(KEY_MANUAL_SELECTED, null) ?: return null
        return getApiKeys(context).firstOrNull { it.hash == hash }?.key
    }

    /**
     * 使用するキーを決める。
     *   1. 手動選択があり、上限未満ならそれ
     *   2. 登録順で上限未満の最初のキー
     *   3. 全て上限なら最後のキー (エラーはサーバ側で 429 として返る)
     */
    fun getActiveApiKey(context: Context): String? {
        val prefs = prefs(context)
        checkAndReset(prefs)

        val entries = getApiKeys(context)
        if (entries.isEmpty()) {
            return null
        }
        val usage = usageData(prefs)

        val manualHash = prefs.getString(KEY_MANUAL_SELECTED, null)
        if (manualHash != null) {
            val manual = entries.firstOrNull { it.hash == manualHash }
            if (manual != null && usage.optInt(manualHash, 0) < FREE_TIER_DAILY_LIMIT) {
                return manual.key
            }
            // 使い切った / 削除されたキーの手動選択は解除
            prefs.edit().remove(KEY_MANUAL_SELECTED).apply()
        }

        return entries.firstOrNull { usage.optInt(it.hash, 0) < FREE_TIER_DAILY_LIMIT }?.key
            ?: entries.last().key
    }

    // -------------------------------------------------------------- internal

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PrefFiles.OPENROUTER, Context.MODE_PRIVATE)
    }

    private fun usageData(prefs: SharedPreferences): JSONObject {
        val json = prefs.getString(KEY_USAGE_DATA, null) ?: return JSONObject()
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "usage data corrupted, resetting", e)
            JSONObject()
        }
    }

    private fun loadEntries(context: Context): List<ApiKeyEntry>? {
        val json = SecurePrefs.getString(context, PrefKeys.SECRET_OPENROUTER_KEYS) ?: return null
        return parseEntries(json)
    }

    private fun parseEntries(json: String): List<ApiKeyEntry> {
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val obj = array.getJSONObject(index)
                ApiKeyEntry(obj.getString(JSON_KEY), obj.optString(JSON_LABEL, "Key #${index + 1}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse key list", e)
            emptyList()
        }
    }

    /**
     * 平文で保存されていた旧形式を一度だけ取り込み、平文側を削除する。
     *   openrouter_prefs/api_keys_v2  [{key,label}]
     *   openrouter_prefs/api_keys     ["key", ...]
     *   settings/openrouter_api_key   "key"
     * 消費量 (usage_data) は生キー → ハッシュに付け替える。
     */
    private fun migrateLegacyKeys(context: Context): List<ApiKeyEntry> {
        val prefs = prefs(context)
        val settings = context.getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)

        val migrated: List<ApiKeyEntry> = when {
            prefs.contains(LEGACY_KEYS_V2) -> parseEntries(prefs.getString(LEGACY_KEYS_V2, "[]") ?: "[]")
            prefs.contains(LEGACY_KEYS_V1) -> {
                val array = JSONArray(prefs.getString(LEGACY_KEYS_V1, "[]") ?: "[]")
                List(array.length()) { ApiKeyEntry(array.getString(it), "Account #${it + 1}") }
            }
            else -> settings.getString(LEGACY_SETTINGS_KEY, null)
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ApiKeyEntry(it, "Primary Account")) }
                .orEmpty()
        }

        if (migrated.isNotEmpty()) {
            saveApiKeys(context, migrated)

            // usage_data: { rawKey: n } → { hash: n }
            prefs.getString(LEGACY_USAGE, null)?.let { legacy ->
                val rekeyed = JSONObject()
                runCatching {
                    val old = JSONObject(legacy)
                    old.keys().forEach { raw -> rekeyed.put(hashOf(raw), old.optInt(raw, 0)) }
                }
                prefs.edit().putString(KEY_USAGE_DATA, rekeyed.toString()).apply()
            }
            prefs.getString(LEGACY_MANUAL, null)?.let { raw ->
                prefs.edit().putString(KEY_MANUAL_SELECTED, hashOf(raw)).apply()
            }
            Log.i(TAG, "migrated ${migrated.size} OpenRouter key(s) to SecurePrefs")
        }

        prefs.edit()
            .remove(LEGACY_KEYS_V2)
            .remove(LEGACY_KEYS_V1)
            .remove(LEGACY_USAGE)
            .remove(LEGACY_MANUAL)
            .apply()
        settings.edit().remove(LEGACY_SETTINGS_KEY).apply()

        return migrated
    }

    /** JST 09:00 を跨いでいたら消費量と手動選択をリセットする */
    private fun checkAndReset(prefs: SharedPreferences) {
        val lastReset = prefs.getLong(KEY_LAST_RESET_TIME, 0L)
        val now = System.currentTimeMillis()

        val boundary = Calendar.getInstance(jstTimeZone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, RESET_HOUR_JST)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis > now) {
                add(Calendar.DAY_OF_MONTH, -1)
            }
        }

        if (lastReset >= boundary.timeInMillis) {
            return
        }
        prefs.edit()
            .putString(KEY_USAGE_DATA, JSONObject().toString())
            .putLong(KEY_LAST_RESET_TIME, now)
            .remove(KEY_MANUAL_SELECTED)
            .apply()
    }

    private fun hashOf(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(HASH_PREFIX_LENGTH)
        for (byte in digest) {
            hex.append(String.format("%02x", byte))
            if (hex.length >= HASH_PREFIX_LENGTH) {
                break
            }
        }
        return hex.substring(0, HASH_PREFIX_LENGTH)
    }
}
