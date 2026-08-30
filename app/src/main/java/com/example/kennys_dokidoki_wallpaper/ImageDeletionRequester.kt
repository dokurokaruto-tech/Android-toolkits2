package com.example.kennys_dokidoki_wallpaper

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File

/**
 * アプリが所有していない画像（他アプリが保存したギャラリー画像など）を
 * 「OS標準の削除承認ダイアログ」を使って削除するための仕掛け。
 *
 * 賢い手の仕組み：
 * 1. まず普通に削除を試す（SAF文書・MediaStore・file://）。
 * 2. Android 11以降でOSにブロックされたら、
 *    MediaStore.createDeleteRequest による「この項目の変更を許可しますか？」
 *    というOS標準ダイアログを出し、ユーザーが許可すれば削除が実行される。
 *    （他アプリ所有の画像でも、アプリに特別な権限は一切不要）
 * 3. file:// でMediaStoreに未登録の画像は、メディアスキャンで一度MediaStoreへ
 *    登録してから同じダイアログで削除する。
 * 4. Android 10以下では書き込み権限を一度だけお願いして file:// を直接消す。
 */
class ImageDeletionRequester(private val activity: ComponentActivity) {

    data class Outcome(
        val deleted: List<ImageEntry>,
        val failed: List<ImageEntry>,
        /** システムの確認画面でユーザーが許可しなかった（または権限を断られた） */
        val userDeclined: Boolean
    )

    private var pendingCallback: ((Outcome) -> Unit)? = null
    private var pendingEntries: List<ImageEntry> = emptyList()

    /** OS標準の削除承認ダイアログ（Android 11+）の結果を受け取るランチャー */
    private val deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val entries = pendingEntries
            val callback = pendingCallback
            pendingEntries = emptyList()
            pendingCallback = null
            callback ?: return@registerForActivityResult
            val approved = result.resultCode == Activity.RESULT_OK
            if (approved) {
                entries.forEach { deleteThumbnailBestEffort(it) }
            }
            callback(
                Outcome(
                    deleted = if (approved) entries else emptyList(),
                    failed = if (approved) emptyList() else entries,
                    userDeclined = !approved
                )
            )
        }

    /** Android 10以下で file:// を消すための書き込み権限リクエスト */
    private val writePermissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val entries = pendingEntries
            val callback = pendingCallback
            pendingEntries = emptyList()
            pendingCallback = null
            callback ?: return@registerForActivityResult
            if (!granted) {
                callback(Outcome(emptyList(), entries, userDeclined = true))
            } else {
                // 権限が降りたので、直接削除をもう一度試す
                runDeletion(entries, callback)
            }
        }

    /**
     * 画像ファイルの削除を要求する。
     * 結果は必ずコールバックで返る（システムダイアログを挟むことがある）。
     */
    fun request(entries: List<ImageEntry>, onComplete: (Outcome) -> Unit) {
        if (entries.isEmpty()) {
            onComplete(Outcome(emptyList(), emptyList(), userDeclined = false))
            return
        }
        // ダイアログを二重に開かないためのガード
        if (pendingCallback != null) {
            onComplete(Outcome(emptyList(), entries, userDeclined = false))
            return
        }
        runDeletion(entries, onComplete)
    }

    private fun runDeletion(entries: List<ImageEntry>, onComplete: (Outcome) -> Unit) {
        val deleted = mutableListOf<ImageEntry>()
        val blocked = mutableListOf<ImageEntry>()

        entries.forEach { entry ->
            if (attemptDirect(entry)) deleted.add(entry) else blocked.add(entry)
        }

        if (blocked.isEmpty()) {
            onComplete(Outcome(deleted, emptyList(), userDeclined = false))
            return
        }

        // Android 10以下：file:// は書き込み権限があれば消せる
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && blocked.any { it.uri.scheme == "file" }) {
            if (!hasLegacyWritePermission()) {
                pendingEntries = blocked
                pendingCallback = { outcome ->
                    onComplete(
                        Outcome(
                            deleted = deleted + outcome.deleted,
                            failed = outcome.failed,
                            userDeclined = outcome.userDeclined
                        )
                    )
                }
                writePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return
            }
            onComplete(Outcome(deleted, blocked, userDeclined = false))
            return
        }

        // Android 11以降：MediaStoreの承認ダイアログで消す
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolveMediaUris(blocked) { resolved ->
                val candidates = resolved.filterValues { it != null }
                val impossible = resolved.filterValues { it == null }.keys.toList()

                if (candidates.isEmpty()) {
                    onComplete(Outcome(deleted, impossible, userDeclined = false))
                    return@resolveMediaUris
                }

                pendingEntries = candidates.keys.toList()
                pendingCallback = { outcome ->
                    onComplete(
                        Outcome(
                            deleted = deleted + outcome.deleted,
                            failed = impossible + outcome.failed,
                            userDeclined = outcome.userDeclined
                        )
                    )
                }

                try {
                    val intentSender = MediaStore.createDeleteRequest(
                        activity.contentResolver,
                        candidates.values.filterNotNull()
                    )
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } catch (e: Exception) {
                    Log.e("ImageDeletionRequester", "createDeleteRequest failed", e)
                    val entriesNow = pendingEntries
                    pendingEntries = emptyList()
                    val callbackNow = pendingCallback
                    pendingCallback = null
                    callbackNow?.invoke(Outcome(emptyList(), entriesNow, userDeclined = false))
                }
            }
            return
        }

        onComplete(Outcome(deleted, blocked, userDeclined = false))
    }

    /** 直接削除を試す。成功したらサムネイルも一緒に掃除する。 */
    private fun attemptDirect(entry: ImageEntry): Boolean {
        if (!DataManager.deleteImageFile(activity, entry.uri)) return false
        deleteThumbnailBestEffort(entry)
        return true
    }

    /** 紐づくサムネイルファイルをベストエフォートで消す（本体の結果には影響させない）。 */
    private fun deleteThumbnailBestEffort(entry: ImageEntry) {
        val thumb = entry.thumbnailUri ?: return
        if (thumb.toString() == entry.uri.toString() || ImageStoragePolicy.isRemote(thumb)) return
        try {
            DataManager.deleteImageFile(activity, thumb)
        } catch (e: Exception) {
            Log.e("ImageDeletionRequester", "thumbnail delete failed: $thumb", e)
        }
    }

    private fun hasLegacyWritePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * エントリをMediaStoreのURIへ解決する。
     * - content://media/... はそのまま使う
     * - file:// はMediaStoreを照合し、見つからなければメディアスキャンで登録してから照合する
     */
    private fun resolveMediaUris(entries: List<ImageEntry>, onResolved: (Map<ImageEntry, Uri?>) -> Unit) {
        val result = LinkedHashMap<ImageEntry, Uri?>()
        val needScan = mutableListOf<Pair<ImageEntry, File>>()

        entries.forEach { entry ->
            val found = mediaUriFor(entry)
            result[entry] = found
            if (found == null && entry.uri.scheme == "file" && !entry.uri.path.isNullOrBlank()) {
                needScan.add(entry to File(entry.uri.path!!))
            }
        }

        if (needScan.isEmpty()) {
            onResolved(result)
            return
        }

        // メディアスキャンしてMediaStoreに登録してからもう一度照合する
        var remaining = needScan.size
        var finished = false
        val handler = Handler(Looper.getMainLooper())
        // スキャンが帰ってこない場所（アプリ専用領域など）に備えた保険
        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                onResolved(result)
            }
        }
        handler.postDelayed(timeoutRunnable, 8000L)

        fun settle(entry: ImageEntry, scannedUri: Uri?) {
            if (finished) return
            result[entry] = scannedUri ?: mediaUriFor(entry)
            remaining--
            if (remaining <= 0) {
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                onResolved(result)
            }
        }

        needScan.forEach { (entry, file) ->
            try {
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(file.absolutePath),
                    arrayOf(guessMime(file))
                ) { _, scannedUri ->
                    handler.post { settle(entry, scannedUri) }
                }
            } catch (e: Exception) {
                Log.e("ImageDeletionRequester", "media scan failed: ${file.absolutePath}", e)
                handler.post { settle(entry, null) }
            }
        }
    }

    private fun mediaUriFor(entry: ImageEntry): Uri? {
        val uri = entry.uri
        if (uri.scheme == "content" && uri.authority == "media") return uri
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        return MediaStoreLocator.find(activity.contentResolver, file)
    }

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "mp4" -> "video/mp4"
        else -> "image/*"
    }
}

/**
 * file:// のパスからMediaStore上のURIを探し出す。
 * 名前だけでなくRELATIVE_PATHやDATAの一致も見て、確実なものを優先する。
 */
object MediaStoreLocator {
    private const val TAG = "MediaStoreLocator"

    fun find(resolver: ContentResolver, file: File): Uri? {
        if (!file.exists() || !file.isFile) return null
        val name = file.name ?: return null
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(name)
        val collections = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external")
        )
        val fallback = mutableListOf<Uri>()

        collections.forEach { base ->
            try {
                resolver.query(base, null, selection, args, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val relColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val expectedRel = relativePathOf(file)
                    while (cursor.moveToNext()) {
                        if (idColumn < 0) continue
                        val id = cursor.getLong(idColumn)
                        val uri = ContentUris.withAppendedId(base, id)
                        if (relColumn >= 0 && expectedRel.isNotEmpty()) {
                            val rel = cursor.getString(relColumn)
                            if (rel != null && rel == expectedRel) return uri
                        }
                        if (dataColumn >= 0) {
                            val data = cursor.getString(dataColumn)
                            if (data != null && data == file.absolutePath) return uri
                        }
                        fallback.add(uri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore query failed for $base", e)
            }
        }
        return fallback.firstOrNull()
    }

    /** 外部ストレージ基準のRELATIVE_PATH（例: "Pictures/Screenshots/"）を組み立てる。 */
    fun relativePathOf(file: File): String {
        val root = try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Exception) {
            return ""
        }
        val parent = file.parentFile?.absolutePath ?: return ""
        if (!parent.startsWith(root)) return ""
        val rel = parent.removePrefix(root).trim('/')
        return if (rel.isEmpty()) "" else "$rel/"
    }
}
