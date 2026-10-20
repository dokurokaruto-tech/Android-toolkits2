package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日記と Google Drive を突き合わせる層。
 * 「どちらが新しいか」は DiarySyncPolicy、HTTP は DriveApi に任せ、
 * ここは日記という意味づけ（1日1ファイル＋貼った画像）だけを扱う。
 *
 *   Drive/Kennys日記/ (旧バージョンから引き継ぐフォルダ名)
 *     2026-09-30.json
 *     library_essay_<uuid>.json / library_wish_<uuid>.json
 *     img_<uuid>.img
 */
object DiaryDriveSync {

    private const val FOLDER_NAME = "Kennys日記"
    private const val TAG = "DiaryDriveSync"

    /** 画面が閉じても書き込みを続けられるよう、アプリ寿命のスコープで動かす */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Outcome(val pushed: Int, val pulled: Int, val error: String?)

    fun isLinked(context: Context): Boolean = DriveSyncPrefs.isLinked(context)

    /** 日記・エッセイ・やりたい事リストの保存後に呼び、ローカル保存を優先して同期する。 */
    fun syncInBackground(context: Context) {
        if (!isLinked(context)) {
            return
        }
        val app = context.applicationContext
        scope.launch {
            val result = sync(app)
            result.error?.let { Log.w(TAG, "バックグラウンド同期に失敗: $it") }
        }
    }

    /** ローカルと Drive を両方向で最新に揃える */
    suspend fun sync(context: Context): Outcome = withContext(Dispatchers.IO) {
        val account = DriveSyncPrefs.account(context)
            ?: return@withContext Outcome(0, 0, "Googleアカウントと連携していません。")
        val token = GoogleDriveAuth.accessToken(context, account)
            ?: return@withContext Outcome(0, 0, "Driveの認可が取得できませんでした。")

        try {
            val folderId = folderId(context, token)
            val remote = DriveApi.listFolder(token, folderId)
            val remoteEntries = remote.mapNotNull { file ->
                DiarySyncPolicy.dateKeyOf(file.name)?.let { it to file }
            }.toMap()
            val remoteImages = remote.mapNotNull { file ->
                DiarySyncPolicy.localImageName(file.name)?.let { it to file }
            }.toMap()
            val remoteNotes = remote.mapNotNull { file ->
                LibraryNoteStore.addressOf(file.name)?.let { file.name to file }
            }.toMap()

            val pushedDiaries = pushLocal(context, token, folderId, remoteEntries, remoteImages)
            val pulledDiaries = pullRemote(context, token, remoteEntries, remoteImages)
            val (pushedNotes, pulledNotes) = syncLibraryNotes(context, token, folderId, remoteNotes)

            DriveSyncPrefs.markSynced(context, System.currentTimeMillis())
            Outcome(pushedDiaries + pushedNotes, pulledDiaries + pulledNotes, null)
        } catch (e: Exception) {
            Outcome(0, 0, e.message ?: "同期に失敗しました。")
        }
    }

    private fun folderId(context: Context, token: String): String {
        DriveSyncPrefs.folderId(context)?.let { return it }
        val created = DriveApi.ensureFolder(token, FOLDER_NAME)
        DriveSyncPrefs.rememberFolder(context, created)
        return created
    }

    /** 端末のほうが新しい日記と、Drive に無い画像を上げる */
    private fun pushLocal(
        context: Context,
        token: String,
        folderId: String,
        remoteEntries: Map<String, DriveFile>,
        remoteImages: Map<String, DriveFile>
    ): Int {
        var pushed = 0
        DiaryStore.savedDates(context).forEach { dateKey ->
            val json = DiaryStore.readRaw(context, dateKey) ?: return@forEach
            val remote = remoteEntries[dateKey]
            val localModified = DiaryStore.entryModifiedMs(context, dateKey)
            if (remote != null && !DiarySyncPolicy.shouldPush(localModified, remote.modifiedAtMs)) {
                return@forEach
            }
            DriveApi.upload(
                token = token,
                folderId = folderId,
                name = DiarySyncPolicy.entryFileName(dateKey),
                mimeType = DiarySyncPolicy.ENTRY_MIME,
                bytes = json.toByteArray(Charsets.UTF_8),
                existingId = remote?.id
            )
            pushImages(context, token, folderId, dateKey, remoteImages)
            pushed++
        }
        return pushed
    }

    private fun pushImages(
        context: Context,
        token: String,
        folderId: String,
        dateKey: String,
        remoteImages: Map<String, DriveFile>
    ) {
        DiaryStore.load(context, dateKey).photos.forEach { photo ->
            if (remoteImages.containsKey(photo.fileName)) {
                return@forEach
            }
            val file = DiaryStore.imageFile(context, photo.fileName)
            if (!file.isFile) {
                return@forEach
            }
            DriveApi.upload(
                token = token,
                folderId = folderId,
                name = DiarySyncPolicy.imageFileName(photo.fileName),
                mimeType = DiarySyncPolicy.IMAGE_MIME,
                bytes = file.readBytes(),
                existingId = null
            )
        }
    }

    /** 題名・本文のノートも、同名ファイル単位でローカルと Drive を揃える。 */
    private fun syncLibraryNotes(
        context: Context,
        token: String,
        folderId: String,
        remoteNotes: Map<String, DriveFile>
    ): Pair<Int, Int> {
        var pushed = 0
        LibraryNoteKind.entries.forEach { kind ->
            LibraryNoteStore.savedIds(context, kind).forEach noteLoop@{ id ->
                val name = LibraryNoteStore.driveFileName(kind, id)
                val raw = LibraryNoteStore.readRaw(context, kind, id) ?: return@noteLoop
                val remote = remoteNotes[name]
                val localModified = LibraryNoteStore.modifiedMs(context, kind, id)
                if (remote != null && !DiarySyncPolicy.shouldPush(localModified, remote.modifiedAtMs)) {
                    return@noteLoop
                }
                DriveApi.upload(
                    token = token,
                    folderId = folderId,
                    name = name,
                    mimeType = DiarySyncPolicy.ENTRY_MIME,
                    bytes = raw.toByteArray(Charsets.UTF_8),
                    existingId = remote?.id
                )
                pushed++
            }
        }

        var pulled = 0
        remoteNotes.forEach { (name, file) ->
            val address = LibraryNoteStore.addressOf(name) ?: return@forEach
            val localModified = LibraryNoteStore.modifiedMs(context, address.kind, address.id)
            if (localModified != 0L && !DiarySyncPolicy.shouldPull(localModified, file.modifiedAtMs)) {
                return@forEach
            }

            val temp = java.io.File(
                context.cacheDir,
                "library_${address.kind.driveKey}_${address.id}_${System.nanoTime()}.tmp"
            )
            try {
                DriveApi.download(token, file.id, temp)
                LibraryNoteStore.writeRaw(context, address.kind, address.id, temp.readText(Charsets.UTF_8))
                pulled++
            } finally {
                temp.delete()
            }
        }
        return pushed to pulled
    }

    /** Drive のほうが新しい日記を取り込み、足りない画像も落とす */
    private fun pullRemote(
        context: Context,
        token: String,
        remoteEntries: Map<String, DriveFile>,
        remoteImages: Map<String, DriveFile>
    ): Int {
        var pulled = 0
        remoteEntries.forEach { (dateKey, file) ->
            val localModified = DiaryStore.entryModifiedMs(context, dateKey)
            if (localModified != 0L && !DiarySyncPolicy.shouldPull(localModified, file.modifiedAtMs)) {
                return@forEach
            }
            val temp = java.io.File(context.cacheDir, "diary_download.tmp")
            DriveApi.download(token, file.id, temp)
            val json = temp.readText(Charsets.UTF_8)
            temp.delete()

            DiaryStore.writeRaw(context, dateKey, json)
            pullImages(context, token, DiaryJson.decode(dateKey, json), remoteImages)
            pulled++
        }
        return pulled
    }

    private fun pullImages(
        context: Context,
        token: String,
        entry: DiaryEntry,
        remoteImages: Map<String, DriveFile>
    ) {
        entry.photos.forEach { photo ->
            if (DiaryStore.hasImage(context, photo.fileName)) {
                return@forEach
            }
            val remote = remoteImages[photo.fileName] ?: return@forEach
            DriveApi.download(token, remote.id, DiaryStore.imageFile(context, photo.fileName))
        }
    }
}
