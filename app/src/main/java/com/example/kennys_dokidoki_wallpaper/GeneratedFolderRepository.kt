package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal object GeneratedFolderRepository {
    private const val PAGE_SIZE = GenerationAgentClient.LIBRARY_PAGE_SIZE
    data class Page(val entries: List<ImageEntry>, val next: String?, val indexing: Boolean)

    suspend fun page(context: Context, date: String, cursor: String?): Page = withContext(Dispatchers.IO) {
        val page = GenerationAgentClient.fetchImagePage(context, date, cursor)
        GeneratedImageDraftStore.seedPage(context, page.images)
        Page(page.images.map {
            GeneratedImageDraftStore.entryFor(context, Uri.parse(it.url), Uri.parse(it.thumbnailUrl))
        }, page.nextCursor, page.indexing)
    }

    /** SAFの1行ごとのisFile/name問い合わせを避け、必要な列をまとめて読む。 */
    suspend fun localBatches(context: Context, folder: Uri, accept: suspend (List<ImageEntry>) -> Unit) = withContext(Dispatchers.IO) {
        val id = if (DocumentsContract.isDocumentUri(context, folder)) {
            DocumentsContract.getDocumentId(folder)
        } else {
            DocumentsContract.getTreeDocumentId(folder)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val batch = ArrayList<ImageEntry>(PAGE_SIZE)
        val cursor = (try {
            context.contentResolver.query(children, columns, null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " DESC")
        } catch (_: IllegalArgumentException) {
            context.contentResolver.query(children, columns, null, null, null)
        }) ?: throw java.io.IOException("フォルダを読み込めません")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                coroutineContext.ensureActive()
                val name = it.getString(nameColumn).orEmpty()
                val mime = it.getString(mimeColumn).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                    (!mime.startsWith("image/") && name.substringAfterLast('.').lowercase() !in setOf("png", "jpg", "jpeg", "webp"))) {
                    continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(folder, it.getString(idColumn))
                batch.add(GeneratedImageDraftStore.entryFor(context, uri))
                if (batch.size == PAGE_SIZE) {
                    accept(batch.toList())
                    batch.clear()
                }
            }
        }
        accept(batch.toList())
    }
}
