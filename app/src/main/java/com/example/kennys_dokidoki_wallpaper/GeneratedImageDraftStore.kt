package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 全画像に入れる前の生成画像用の一時データ。タグ・説明・仮チャットの紐づけを持つ。
 */
object GeneratedImageDraftStore {
    private const val DIR_NAME = "generated_drafts"
    private const val FILE_NAME = "drafts.json"
    private val lock = Any()
    private var cache: MutableMap<String, GeneratedImageLifecycle.Draft>? = null

    fun keyFor(uri: Uri): String = GeneratedImageIdentity.canonicalKey(uri.toString())

    fun entryFor(
        context: Context,
        uri: Uri,
        thumbnailUri: Uri? = null,
        generatedTags: Collection<String> = emptyList()
    ): ImageEntry {
        if (generatedTags.isNotEmpty()) {
            seedGeneratedTags(context, uri, generatedTags)
        }
        val draft = get(context, keyFor(uri))
        return ImageEntry(
            uri = uri,
            tags = draft?.tags?.toMutableSet() ?: mutableSetOf(),
            description = draft?.description,
            linkedChatId = draft?.linkedChatId,
            thumbnailUri = thumbnailUri
        )
    }

    fun seedGeneratedTags(context: Context, uri: Uri, generatedTags: Collection<String>) {
        seedGeneratedSource(context, uri, generatedTags)
    }

    fun seedGeneratedSource(
        context: Context,
        uri: Uri,
        generatedTags: Collection<String> = emptyList(),
        cardStates: Map<String, Int> = emptyMap(),
        width: Int? = null,
        height: Int? = null,
        steps: Int? = null,
        sampler: String? = null,
        prompt: String? = null,
        randomPickedIds: Set<String> = emptySet(),
        randomEnabledCategories: Set<String> = emptySet(),
        seed: Long? = null,
        negativePrompt: String? = null
    ) {
        val incoming = GeneratedImageTagBinding.collect(listOf(generatedTags))
        val key = keyFor(uri)
        val existing = get(context, key)
        val mergedTags = if (incoming.isEmpty()) {
            existing?.tags ?: emptySet()
        } else {
            GeneratedImageTagBinding.mergeForBrowse(existing?.tags ?: emptySet(), incoming)
        }
        val mergedCards = if (cardStates.isNotEmpty()) cardStates else existing?.cardStates.orEmpty()
        val next = GeneratedImageLifecycle.Draft(
            tags = mergedTags,
            description = existing?.description,
            linkedChatId = existing?.linkedChatId,
            cardStates = mergedCards,
            width = width ?: existing?.width,
            height = height ?: existing?.height,
            steps = steps ?: existing?.steps,
            sampler = sampler ?: existing?.sampler,
            prompt = prompt?.takeIf { it.isNotBlank() } ?: existing?.prompt,
            negativePrompt = negativePrompt?.takeIf { it.isNotBlank() } ?: existing?.negativePrompt,
            seed = GeneratedImageReplayPolicy.parseSeed(seed) ?: existing?.seed,
            randomPickedIds = if (randomPickedIds.isNotEmpty()) randomPickedIds else existing?.randomPickedIds.orEmpty(),
            randomEnabledCategories = if (randomEnabledCategories.isNotEmpty()) {
                randomEnabledCategories
            } else {
                existing?.randomEnabledCategories.orEmpty()
            }
        )
        if (existing == next) return
        upsert(context, key, next)
    }

    fun get(context: Context, key: String): GeneratedImageLifecycle.Draft? {
        if (key.isBlank()) return null
        synchronized(lock) {
            return loadLocked(context)[key]
        }
    }

    fun save(context: Context, uri: Uri, entry: ImageEntry) {
        val existing = get(context, keyFor(uri))
        upsert(
            context,
            keyFor(uri),
            GeneratedImageLifecycle.Draft(
                tags = entry.tags.toSet(),
                description = entry.description,
                linkedChatId = entry.linkedChatId,
                cardStates = existing?.cardStates.orEmpty(),
                width = existing?.width,
                height = existing?.height,
                steps = existing?.steps,
                sampler = existing?.sampler,
                prompt = existing?.prompt,
                negativePrompt = existing?.negativePrompt,
                seed = existing?.seed,
                randomPickedIds = existing?.randomPickedIds.orEmpty(),
                randomEnabledCategories = existing?.randomEnabledCategories.orEmpty()
            )
        )
        val chatId = entry.linkedChatId
        ChatSessionManager.setLinkedChatId(context, keyFor(uri), chatId)
    }

    fun upsert(context: Context, key: String, draft: GeneratedImageLifecycle.Draft) {
        if (key.isBlank()) return
        synchronized(lock) {
            val map = loadLocked(context)
            map[key] = draft
            persistLocked(context, map)
        }
    }

    fun applyTo(context: Context, entry: ImageEntry) {
        val draft = get(context, keyFor(entry.uri)) ?: return
        if (entry.tags.isEmpty() && draft.tags.isNotEmpty()) {
            entry.tags.addAll(draft.tags)
        }
        if (entry.description.isNullOrBlank() && !draft.description.isNullOrBlank()) {
            entry.description = draft.description
        }
        if (entry.linkedChatId.isNullOrBlank() && !draft.linkedChatId.isNullOrBlank()) {
            entry.linkedChatId = draft.linkedChatId
        }
    }

    fun migrateOnImport(context: Context, sourceUri: Uri, destUri: Uri): ImageEntry {
        val sourceKey = keyFor(sourceUri)
        val destKey = GeneratedImageIdentity.canonicalKey(destUri.toString())
        val draft = get(context, sourceKey) ?: GeneratedImageLifecycle.Draft(
            linkedChatId = ChatSessionManager.getLinkedChatId(context, sourceKey)
                ?: ChatSessionManager.getLinkedChatId(context, sourceUri.toString())
        )
        val sessionName = draft.linkedChatId?.let { ChatSessionManager.getSessionName(context, it) }
        val plan = GeneratedImageLifecycle.importDraft(destKey, draft, sessionName)

        val imported = ImageEntry(
            uri = destUri,
            tags = plan.tags.toMutableSet(),
            linkedChatId = plan.chatId,
            description = plan.description
        )
        if (draft.cardStates.isNotEmpty() || draft.width != null) {
            seedGeneratedSource(
                context,
                destUri,
                draft.tags,
                draft.cardStates,
                draft.width,
                draft.height,
                draft.steps,
                draft.sampler,
                draft.prompt,
                draft.randomPickedIds,
                draft.randomEnabledCategories,
                draft.seed,
                draft.negativePrompt
            )
        }
        if (!plan.chatId.isNullOrBlank()) {
            ChatSessionManager.setLinkedChatId(context, destKey, plan.chatId)
            ChatSessionManager.setLinkedChatId(context, destUri.toString(), plan.chatId)
            imported.linkedChatId = plan.chatId
            if (!plan.persistedSessionName.isNullOrBlank()) {
                ChatSessionManager.renameSession(context, plan.chatId, plan.persistedSessionName)
            }
        }
        delete(context, sourceKey)
        return imported
    }

    fun deleteForImage(context: Context, uri: Uri) {
        deleteImageAndMaybeChat(context, uri)
    }

    fun deleteImageAndMaybeChat(context: Context, uri: Uri) {
        val key = keyFor(uri)
        val linked = get(context, key)?.linkedChatId
            ?: ChatSessionManager.getLinkedChatId(context, key)
            ?: ChatSessionManager.getLinkedChatId(context, uri.toString())
        val otherKeys = keysUsingChat(context, linked).toMutableSet()
        DataManager.allImages.forEach { entry ->
            if (!linked.isNullOrBlank() && entry.linkedChatId == linked) {
                otherKeys.add(GeneratedImageIdentity.canonicalKey(entry.uri.toString()))
            }
        }
        val plan = GeneratedImageLifecycle.deletePlan(key, linked, otherKeys)
        plan.unlinkKeys.forEach { unlinkKey ->
            delete(context, unlinkKey)
            ChatSessionManager.setLinkedChatId(context, unlinkKey, null)
        }
        ChatSessionManager.setLinkedChatId(context, uri.toString(), null)
        plan.chatIdToDelete?.let { ChatSessionManager.deleteSession(context, it) }
    }

    fun delete(context: Context, key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            val map = loadLocked(context)
            if (map.remove(key) != null) persistLocked(context, map)
        }
    }

    private fun keysUsingChat(context: Context, chatId: String?): Set<String> {
        if (chatId.isNullOrBlank()) return emptySet()
        synchronized(lock) {
            return loadLocked(context)
                .filterValues { it.linkedChatId == chatId }
                .keys
        }
    }

    private fun draftsFile(context: Context): File {
        return File(File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }, FILE_NAME)
    }

    private fun loadLocked(context: Context): MutableMap<String, GeneratedImageLifecycle.Draft> {
        cache?.let { return it }
        val map = mutableMapOf<String, GeneratedImageLifecycle.Draft>()
        val json = AtomicFiles.readUtf8(draftsFile(context))
        if (!json.isNullOrBlank()) {
            runCatching {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    val item = obj.optJSONObject(key) ?: return@forEach
                    val tags = mutableSetOf<String>()
                    val tagsArray = item.optJSONArray("tags") ?: JSONArray()
                    for (index in 0 until tagsArray.length()) {
                        tags.add(tagsArray.getString(index))
                    }
                    map[key] = GeneratedImageLifecycle.Draft(
                        tags = tags,
                        description = item.optString("description").takeIf { it.isNotBlank() && it != "null" },
                        linkedChatId = item.optString("linkedChatId").takeIf { it.isNotBlank() && it != "null" },
                        cardStates = GeneratedImageTagBinding.parseCardStates(item.optJSONObject("cardStates")),
                        width = item.optInt("width", 0).takeIf { it > 0 },
                        height = item.optInt("height", 0).takeIf { it > 0 },
                        steps = item.optInt("steps", 0).takeIf { it > 0 },
                        sampler = item.optString("sampler").takeIf { it.isNotBlank() && it != "null" },
                        prompt = item.optString("prompt").takeIf { it.isNotBlank() && it != "null" },
                        negativePrompt = item.optString("negativePrompt").takeIf { it.isNotBlank() && it != "null" },
                        seed = item.optLong("seed", -1L).takeIf { it >= 0L },
                        randomPickedIds = GeneratedImageTagBinding.parseStringSet(item.optJSONArray("randomPickedIds")),
                        randomEnabledCategories = GeneratedImageTagBinding.parseStringSet(item.optJSONArray("randomEnabledCategories"))
                    )
                }
            }
        }
        cache = map
        return map
    }

    private fun persistLocked(context: Context, map: MutableMap<String, GeneratedImageLifecycle.Draft>) {
        cache = map
        val obj = JSONObject()
        map.forEach { (key, draft) ->
            obj.put(key, JSONObject().apply {
                put("tags", JSONArray().also { array -> draft.tags.forEach { array.put(it) } })
                put("description", draft.description ?: JSONObject.NULL)
                put("linkedChatId", draft.linkedChatId ?: JSONObject.NULL)
                put("cardStates", JSONObject().also { states ->
                    draft.cardStates.forEach { (id, level) -> states.put(id, level) }
                })
                put("width", draft.width ?: JSONObject.NULL)
                put("height", draft.height ?: JSONObject.NULL)
                put("steps", draft.steps ?: JSONObject.NULL)
                put("sampler", draft.sampler ?: JSONObject.NULL)
                put("prompt", draft.prompt ?: JSONObject.NULL)
                put("negativePrompt", draft.negativePrompt ?: JSONObject.NULL)
                put("seed", draft.seed ?: JSONObject.NULL)
                put("randomPickedIds", GeneratedImageTagBinding.encodeStringSet(draft.randomPickedIds))
                put("randomEnabledCategories", GeneratedImageTagBinding.encodeStringSet(draft.randomEnabledCategories))
            })
        }
        AtomicFiles.writeUtf8(draftsFile(context), obj.toString())
    }
}
