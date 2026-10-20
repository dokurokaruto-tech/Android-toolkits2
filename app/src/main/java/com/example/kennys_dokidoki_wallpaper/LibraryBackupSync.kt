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
import java.io.File
import java.io.IOException
import java.util.UUID

/** SAFで選ばれた1つのバックアップファイルを更新する。Google OAuth/APIは使わない。 */
internal object LibraryBackupSync {

    private const val PREFS_NAME = "library_backup_file"
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "display_name"
    private const val KEY_LAST_BACKUP_MS = "last_backup_ms"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LOCAL_REVISION = "local_revision"
    private const val KEY_BACKED_UP_REVISION = "backed_up_revision"
    private const val KEY_COUNTS_VALID = "backup_counts_valid"
    private const val KEY_DIARY_COUNT = "backup_diary_count"
    private const val KEY_ESSAY_COUNT = "backup_essay_count"
    private const val KEY_WISH_LIST_COUNT = "backup_wish_list_count"
    private const val KEY_IMAGE_COUNT = "backup_image_count"
    private const val KEY_PAYLOAD_BYTES = "backup_payload_bytes"

    private val mutex = Mutex()
    private val revisionLock = Any()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Outcome(
        val success: Boolean,
        val message: String,
        val requiresOverwriteConfirmation: Boolean = false
    )

    private data class StagedArchive(val file: File, val counts: LibraryBackupCounts)

    fun selectedUri(context: Context): Uri? = preferences(context).getString(KEY_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun selectedName(context: Context): String? = preferences(context).getString(KEY_NAME, null)

    fun lastBackupMs(context: Context): Long = preferences(context).getLong(KEY_LAST_BACKUP_MS, 0L)

    fun lastError(context: Context): String? = preferences(context).getString(KEY_LAST_ERROR, null)

    fun hasBackupCounts(context: Context): Boolean =
        preferences(context).getBoolean(KEY_COUNTS_VALID, false)

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

    /** アプリ復帰時に、前回の更新が中断／失敗していた場合だけ再試行する。 */
    suspend fun backupIfDirty(context: Context): Outcome? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val uri = selectedUri(appContext) ?: return@withLock null
            if (!isDirty(appContext)) return@withLock null
            backupLocked(appContext, uri, allowDestructiveOverwrite = false)
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
            val alreadyHasRead = permissions.any { it.uri == uri && it.isReadPermission }
            val alreadyHasWrite = permissions.any { it.uri == uri && it.isWritePermission }
            val alreadyHasBoth = alreadyHasRead && alreadyHasWrite
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

                val createDistinctFile = isNewFile && oldUri != uri
                val merge = if (createDistinctFile) {
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
                val staged = stageArchive(appContext)
                val counts = try {
                    val warning = if (oldUri == uri && !createDistinctFile) {
                        overwriteWarningForExistingTarget(appContext, staged.counts)
                    } else {
                        null
                    }
                    if (warning != null) {
                        val message = overwriteProtectionMessage(warning)
                        rememberFailure(appContext, message)
                        return@withLock Outcome(false, message, requiresOverwriteConfirmation = true)
                    }
                    writeStagedArchive(appContext, uri, staged.file)
                    staged.counts
                } finally {
                    staged.file.delete()
                }
                val name = queryDisplayName(appContext, uri)
                preferences(appContext).edit()
                    .putString(KEY_URI, uri.toString())
                    .putString(KEY_NAME, name)
                    .putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis())
                    .putLong(KEY_BACKED_UP_REVISION, revision)
                    .putBackupCounts(counts)
                    .remove(KEY_LAST_ERROR)
                    .apply()

                if (oldUri != null && oldUri != uri) {
                    releasePermission(resolver, oldUri)
                }

                val message = if (createDistinctFile) {
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

    suspend fun restoreNow(context: Context): Outcome {
        val appContext = context.applicationContext
        val uri = selectedUri(appContext)
            ?: return Outcome(false, "先にバックアップファイルを選択してください。")
        val permissions = appContext.contentResolver.persistedUriPermissions
        val canRead = permissions.any { it.uri == uri && it.isReadPermission }
        val canWrite = permissions.any { it.uri == uri && it.isWritePermission }
        if (!canRead || !canWrite) {
            return Outcome(false, "保存先へのアクセス権が無効です。バックアップファイルを選び直してください。")
        }
        // 既に永続化された権限で同じファイルを再読込し、ローカルにない項目をマージする。
        return connect(appContext, uri, returnedFlags = 0, isNewFile = false)
    }

    suspend fun backupNow(
        context: Context,
        allowDestructiveOverwrite: Boolean = false
    ): Outcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val appContext = context.applicationContext
            val uri = selectedUri(appContext)
                ?: return@withLock Outcome(false, "先にバックアップファイルを選択してください。")
            backupLocked(appContext, uri, allowDestructiveOverwrite)
        }
    }

    private fun backupLocked(
        context: Context,
        uri: Uri,
        allowDestructiveOverwrite: Boolean
    ): Outcome {
        var staged: StagedArchive? = null
        try {
            val revision = localRevision(context)
            staged = stageArchive(context)
            val previousCounts = storedBackupCounts(context)
            val warning = if (previousCounts == null) {
                "この保存先の前回バックアップ件数を確認できません。"
            } else {
                LibraryBackupSafetyPolicy.overwriteWarning(previousCounts, staged.counts)
            }
            if (warning != null && !allowDestructiveOverwrite) {
                val message = overwriteProtectionMessage(warning)
                rememberFailure(context, message)
                return Outcome(false, message, requiresOverwriteConfirmation = true)
            }

            writeStagedArchive(context, uri, staged.file)
            preferences(context).edit()
                .putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis())
                .putLong(KEY_BACKED_UP_REVISION, revision)
                .putBackupCounts(staged.counts)
                .remove(KEY_LAST_ERROR)
                .apply()
            return Outcome(true, "バックアップファイルを更新しました。")
        } catch (error: Exception) {
            val message = failureMessage(error)
            rememberFailure(context, message)
            return Outcome(false, message)
        } finally {
            staged?.file?.delete()
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
                .remove(KEY_COUNTS_VALID)
                .remove(KEY_DIARY_COUNT)
                .remove(KEY_ESSAY_COUNT)
                .remove(KEY_WISH_LIST_COUNT)
                .remove(KEY_IMAGE_COUNT)
                .remove(KEY_PAYLOAD_BYTES)
                .apply()
        }
    }

    private fun stageArchive(context: Context): StagedArchive {
        val file = File(context.cacheDir, "library-backup-${UUID.randomUUID()}.zip")
        return try {
            val counts = file.outputStream().use { LibraryBackupArchive.write(context, it) }
            StagedArchive(file, counts)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun writeStagedArchive(context: Context, uri: Uri, stagedFile: File) {
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("バックアップファイルに書き込めませんでした。保存先の空き容量や通信状態を確認してください。")
        output.use { destination ->
            stagedFile.inputStream().use { source -> source.copyTo(destination) }
        }
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

    private fun overwriteWarningForExistingTarget(
        context: Context,
        currentCounts: LibraryBackupCounts
    ): String? {
        val previous = storedBackupCounts(context)
        if (previous != null) {
            return LibraryBackupSafetyPolicy.overwriteWarning(previous, currentCounts)
        }
        return if (currentCounts.totalRecords == 0 && currentCounts.images == 0) {
            "この保存先の前回バックアップ件数を確認できず、端末側にも記録がありません。"
        } else {
            null
        }
    }

    private fun overwriteProtectionMessage(warning: String): String =
        "$warning\n\n安全のため、クラウドファイルへの上書きを停止しました。消えたデータを戻す場合は「選択済みバックアップから復元」を実行してください。意図した削除なら、内容を確認してから手動バックアップの上書きを承認できます。"

    private fun storedBackupCounts(context: Context): LibraryBackupCounts? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_COUNTS_VALID, false)) return null
        return LibraryBackupCounts(
            diaries = prefs.getInt(KEY_DIARY_COUNT, 0),
            essays = prefs.getInt(KEY_ESSAY_COUNT, 0),
            wishLists = prefs.getInt(KEY_WISH_LIST_COUNT, 0),
            images = prefs.getInt(KEY_IMAGE_COUNT, 0),
            payloadBytes = prefs.getLong(KEY_PAYLOAD_BYTES, 0L)
        )
    }

    private fun SharedPreferences.Editor.putBackupCounts(counts: LibraryBackupCounts): SharedPreferences.Editor =
        putBoolean(KEY_COUNTS_VALID, true)
            .putInt(KEY_DIARY_COUNT, counts.diaries)
            .putInt(KEY_ESSAY_COUNT, counts.essays)
            .putInt(KEY_WISH_LIST_COUNT, counts.wishLists)
            .putInt(KEY_IMAGE_COUNT, counts.images)
            .putLong(KEY_PAYLOAD_BYTES, counts.payloadBytes)

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
