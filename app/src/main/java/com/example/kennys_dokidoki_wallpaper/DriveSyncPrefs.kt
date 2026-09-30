package com.example.kennys_dokidoki_wallpaper

import android.content.Context

/** Drive 連携の設定。連携したアカウントと、作った日記フォルダのIDを覚える */
object DriveSyncPrefs {

    private const val PREFS_NAME = "diary_drive"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_FOLDER_ID = "folder_id"
    private const val KEY_LAST_SYNC = "last_sync_ms"

    fun account(context: Context): String? = prefs(context).getString(KEY_ACCOUNT, null)

    fun isLinked(context: Context): Boolean = account(context) != null

    fun link(context: Context, accountEmail: String) {
        prefs(context).edit().putString(KEY_ACCOUNT, accountEmail).remove(KEY_FOLDER_ID).apply()
    }

    fun unlink(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun folderId(context: Context): String? = prefs(context).getString(KEY_FOLDER_ID, null)

    fun rememberFolder(context: Context, folderId: String) {
        prefs(context).edit().putString(KEY_FOLDER_ID, folderId).apply()
    }

    fun lastSyncMs(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC, 0L)

    fun markSynced(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, atMs).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
