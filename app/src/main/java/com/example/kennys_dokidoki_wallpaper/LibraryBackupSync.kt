package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** SAFで選ばれた1つのバックアップファイルを更新する。Google OAuth/APIは使わない。 */
internal object LibraryBackupSync {

    private const val PREFS_NAME = "library_backup_file"
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "display_name"
    private const val KEY_LAST_BACKUP_MS = "last_backup_ms"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LOCAL_REVISION = "local_revision"
    private const val KEY_BACKED_UP_REVISION = "backed_up_revision"

    private val mutex = Mutex()
    private val revisionLock = Any()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Outcome(val success: Boolean, val message: String)

    fun selectedUri(context: Context): Uri? = preferences(context).getString(KEY_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun selectedName(context: Context): String? = preferences(context).getString(KEY_NAME, null)

    fun lastBackupMs(context: Context): Long = preferences(context).getLong(KEY_LAST_BACKUP_MS, 0L)

    fun lastError(context: Context): String? = preferences(context).getString(KEY_LAST_ERROR, null)

    fun isDirty(context: Context): Boolean {
        val prefs = preferences(context)
        return prefs.getLong(KEY_LOCAL_REVISION, 0L) > prefs.getLong(KEY_BACKED_UP_REVISION, 0L)
    }

    /** 保存処理の直後に呼ぶ。保存先未選択なら、後から接続した時に書き出すため印だけ残す。 */
    fun markDirty(context: Context) {
        synchronized(revisionLock) {
            val prefs = preferences(context)
            prefs.edit()
                .putLong(KEY_LOCAL_REVISION, prefs.getLong(KEY_LOCAL_REVISION, 0L) + 1L)
                .apply()
        }
    }

    fun backupInBackground(context: Context) {
        val appContext = context.applicationContext
        if (selectedUri(appContext) == null || !isDirty(appContext)) return
        backgroundScope.launch { backupIfDirty(appContext) }
    }

    /** アプリ復帰時に、前回失敗・中断した更新だけを再試行する。 */
    suspend fun backupIfDirty(context: Context): Outcome? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val uri = selectedUri(appContext) ?: return@withLock null
            if (!isDirty(appContext)) return@withLock null
            backupLocked(appContext, uri)
        }
    }

    suspend fun connect(
        context: Context,
        uri: Uri,
        returnedFlags: Int,
        isNewFile: Boolean
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            if (uri.scheme != "content") {
                return@withLock Outcome(false, "ファイル選択画面から有効なバックアップ先を選んでください。")
            }

            val resolver = appContext.contentResolver
            val oldUri = selectedUri(appContext)
            val permissions = resolver.persistedUriPermissions
            val hadAnyPermission = permissions.any { it.uri == uri }
            val alreadyHasBoth = permissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }
            val requestedReadWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val returnedReadWrite = returnedFlags and requestedReadWrite

            if (!alreadyHasBoth &&
                (returnedReadWrite != requestedReadWrite ||
                    (returnedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0)
            ) {
                return@withLock Outcome(
                    false,
                    "選択した保存先が読み書き権限を記憶できません。Google Driveなど、永続的なアクセスに対応した保存先を選んでください。"
                )
            }

            var tookNewPermission = false
            try {
                if (!alreadyHasBoth) {
                    resolver.takePersistableUriPermission(uri, requestedReadWrite)
                    tookNewPermission = true
                }

                val merge = if (isNewFile) {
                    null
                } else {
                    val input = resolver.openInputStream(uri)
                        ?: throw IOException("選択したバックアップファイルを開けませんでした。")
                    input.use { LibraryBackupArchive.merge(appContext, it) }
                }
                if (merge != null &&
                    (merge.importedDiaries > 0 || merge.importedNotes > 0 || merge.importedImages > 0)
                ) {
                    markDirty(appContext)
                }

                val revision = localRevision(appContext)
                writeArchive(appContext, uri)
                val name = queryDisplayName(appContext, uri)
                preferences(appContext).edit()
                    .putString(KEY_URI, uri.toString())
                    .putString(KEY_NAME, name)
                    .putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis())
                    .putLong(KEY_BACKED_UP_REVISION, revision)
                    .remove(KEY_LAST_ERROR)
                    .apply()

                if (oldUri != null && oldUri != uri) {
                    releasePermission(resolver, oldUri)
                }

                val message = if (isNewFile) {
                    "バックアップを作成しました。日記やノートの保存後、このファイルを自動更新します。"
                } else {
                    val result = merge ?: LibraryBackupArchive.MergeResult(0, 0, 0)
                    "既存データを残したまま統合し、バックアップを更新しました。取り込み: 日記${result.importedDiaries}件・ノート${result.importedNotes}件・画像${result.importedImages}件。"
                }
                Outcome(true, message)
            } catch (error: Exception) {
                if (tookNewPermission && !hadAnyPermission && oldUri != uri) {
                    releasePermission(resolver, uri)
                }
                val message = failureMessage(error)
                if (oldUri == uri) rememberFailure(appContext, message)
                Outcome(false, message)
            }
        }
    }

    suspend fun backupNow(context: Context): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val uri = selectedUri(appContext)
                ?: return@withLock Outcome(false, "先にバックアップファイルを選択してください。")
            backupLocked(appContext, uri)
        }
    }

    private fun backupLocked(context: Context, uri: Uri): Outcome {
        return try {
            val revision = localRevision(context)
            writeArchive(context, uri)
            preferences(context).edit()
                .putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis())
                .putLong(KEY_BACKED_UP_REVISION, revision)
                .remove(KEY_LAST_ERROR)
                .apply()
            Outcome(true, "バックアップファイルを更新しました。")
        } catch (error: Exception) {
            val message = failureMessage(error)
            rememberFailure(context, message)
            Outcome(false, message)
        }
    }

    suspend fun disconnect(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            selectedUri(appContext)?.let { releasePermission(appContext.contentResolver, it) }
            preferences(appContext).edit()
                .remove(KEY_URI)
                .remove(KEY_NAME)
                .remove(KEY_LAST_BACKUP_MS)
                .remove(KEY_LAST_ERROR)
                .apply()
        }
    }

    private fun writeArchive(context: Context, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("バックアップファイルに書き込めませんでした。保存先の空き容量や通信状態を確認してください。")
        output.use { LibraryBackupArchive.write(context, it) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: LibraryBackupArchive.SUGGESTED_FILE_NAME
    }

    private fun rememberFailure(context: Context, message: String) {
        preferences(context).edit()
            .putString(KEY_LAST_ERROR, message)
            .apply()
    }

    private fun failureMessage(error: Exception): String {
        val detail = error.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
        return if (detail == null) {
            "バックアップを更新できませんでした。保存先へのアクセスを確認してください。"
        } else {
            "バックアップを更新できませんでした: $detail"
        }
    }

    private fun releasePermission(resolver: android.content.ContentResolver, uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 許可が既に失効している場合も、設定解除そのものは続ける。
        }
    }

    private fun localRevision(context: Context): Long =
        preferences(context).getLong(KEY_LOCAL_REVISION, 0L)

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
