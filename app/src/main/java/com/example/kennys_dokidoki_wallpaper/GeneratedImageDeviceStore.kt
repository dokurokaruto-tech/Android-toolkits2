package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一覧と全画面で保存処理を共用。全画面を閉じてもダウンロードは継続する。 */
object GeneratedImageDeviceStore {
    data class Saved(val uri: Uri, val addedToLibrary: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val active = MutableStateFlow<String?>(null)
    val savingUri = active.asStateFlow()

    fun saveInBackground(context: Context, source: Uri) {
        if (active.value != null) {
            return
        }
        val ref = GeneratedImageIdentity.remoteRef(source.toString()) ?: return
        val app = context.applicationContext
        Toast.makeText(app, R.string.viewer_save_in_progress, Toast.LENGTH_SHORT).show()
        scope.launch {
            val saved = save(app, source, ref.date)
            Toast.makeText(app, if (saved != null) R.string.viewer_save_success else R.string.viewer_save_failed,
                Toast.LENGTH_LONG).show()
        }
    }

    suspend fun save(context: Context, source: Uri, date: String): Saved? = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            active.value = source.toString()
            try {
                val local = GeneratedImageImporter.download(context, source, date) ?: return@withLock null
                val draft = GeneratedImageDraftStore.get(context, GeneratedImageDraftStore.keyFor(source))
                val imported = GeneratedImageDraftStore.migrateOnImport(context, source, local)
                val existing = DataManager.allImages.find { it.uri == local }
                if (existing == null) {
                    DataManager.allImages.add(0, imported)
                } else {
                    if (existing.tags.isEmpty()) {
                        existing.tags.addAll(imported.tags)
                    }
                    if (existing.description.isNullOrBlank()) {
                        existing.description = imported.description
                    }
                    if (existing.linkedChatId.isNullOrBlank()) {
                        existing.linkedChatId = imported.linkedChatId
                    }
                }
                // 保存後も閲覧中のPC画像を同じシードで再生成できるよう、生成条件を残す。
                draft?.let {
                    GeneratedImageDraftStore.seedGeneratedSource(context, source, it.tags, it.cardStates,
                        it.width, it.height, it.steps, it.sampler, it.prompt, it.randomPickedIds,
                        it.randomEnabledCategories, it.seed, it.negativePrompt)
                }
                DataManager.saveData(context)
                Saved(local, existing == null)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                Log.w("GeneratedDeviceStore", "Device save failed", error)
                null
            } finally {
                active.value = null
            }
        }
    }
}
