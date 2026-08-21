package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatNode(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    val isUser: Boolean,
    val parentId: String? = null,
    val childrenIds: MutableList<String> = mutableListOf(),
    var lastActiveChildId: String? = null,
    var modelName: String? = null,
    var giftKey: String? = null,
    var suggestionA: String? = null,
    var suggestionB: String? = null,
    var suggestionC: String? = null,
    var giftRequestYen: Int? = null
)

data class ChatTree(
    val nodes: MutableMap<String, ChatNode>,
    var currentNodeId: String?
)

object ChatSessionManager {
    private const val TAG = "ChatSessionManager"
    private const val PREFS_NAME = "chat_sessions"
    private const val KEY_SESSIONS = "sessions_list"
    private const val DIR_NAME = "chat_sessions"
    private const val INDEX_FILE = "sessions_index.json"
    private const val LINKS_FILE = "image_links.json"

    private val lock = Any()
    private var linksCache: MutableMap<String, String>? = null

    fun getAllSessions(context: Context): List<Pair<String, String>> {
        synchronized(lock) {
            return loadIndex(context)
        }
    }

    fun getSessionName(context: Context, sessionId: String): String? {
        return getAllSessions(context).firstOrNull { it.first == sessionId }?.second
    }

    fun createNewSession(context: Context, name: String): String {
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            val list = loadIndex(context).toMutableList()
            list.add(id to name)
            saveIndex(context, list)
        }
        return id
    }

    fun renameSession(context: Context, sessionId: String, newName: String) {
        synchronized(lock) {
            val list = loadIndex(context).map { (id, name) ->
                if (id == sessionId) id to newName else id to name
            }
            saveIndex(context, list)
        }
    }

    fun deleteSession(context: Context, sessionId: String) {
        synchronized(lock) {
            val list = loadIndex(context).filter { it.first != sessionId }
            saveIndex(context, list)

            val file = sessionFile(context, sessionId)
            if (file.exists()) file.delete()

            val historyPrefs = context.getSharedPreferences("chat_history_$sessionId", Context.MODE_PRIVATE)
            historyPrefs.edit().clear().apply()

            val links = loadLinksLocked(context)
            val removed = links.entries.removeAll { it.value == sessionId }
            if (removed) saveLinksLocked(context, links)
        }
    }

    fun loadSessionData(context: Context, sessionId: String): ChatTree {
        if (sessionId.isBlank()) return ChatTree(mutableMapOf(), null)
        synchronized(lock) {
            val file = sessionFile(context, sessionId)
            val fileJson = AtomicFiles.readUtf8(file)
            if (!fileJson.isNullOrBlank()) {
                return deserializeTree(fileJson)
            }

            val prefs = context.getSharedPreferences("chat_history_$sessionId", Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("history", "[]") ?: "[]"
            val tree = deserializeTree(jsonStr)
            if (tree.nodes.isNotEmpty()) {
                AtomicFiles.writeUtf8(file, serializeTree(tree))
            }
            return tree
        }
    }

    fun saveSessionData(context: Context, sessionId: String, tree: ChatTree) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            try {
                AtomicFiles.writeUtf8(sessionFile(context, sessionId), serializeTree(tree))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session $sessionId", e)
            }
        }
    }

    fun getLinkedChatId(context: Context, imageUri: String): String? {
        if (imageUri.isBlank()) return null
        synchronized(lock) {
            return loadLinksLocked(context)[imageUri]
        }
    }

    fun setLinkedChatId(context: Context, imageUri: String, chatId: String?) {
        if (imageUri.isBlank()) return
        synchronized(lock) {
            val links = loadLinksLocked(context)
            links[imageUri] = chatId ?: ""
            saveLinksLocked(context, links)
        }
    }

    fun setLinkedChatIds(context: Context, updates: Map<String, String?>) {
        if (updates.isEmpty()) return
        synchronized(lock) {
            val links = loadLinksLocked(context)
            updates.forEach { (uri, chatId) ->
                if (uri.isBlank()) return@forEach
                links[uri] = chatId ?: ""
            }
            saveLinksLocked(context, links)
        }
    }

    /**
     * 画像リストへ、軽量リンクファイルの紐づけを適用する。
     * 巨大な all_images 保存が欠けても、こちらが正になる。
     */
    fun applyImageLinks(context: Context, images: List<ImageEntry>) {
        synchronized(lock) {
            val links = loadLinksLocked(context)
            var dirty = false
            images.forEach { entry ->
                val uri = entry.uri.toString()
                if (links.containsKey(uri)) {
                    entry.linkedChatId = links[uri].takeUnless { it.isNullOrBlank() }
                } else if (!entry.linkedChatId.isNullOrBlank()) {
                    links[uri] = entry.linkedChatId!!
                    dirty = true
                }
            }
            if (dirty) saveLinksLocked(context, links)
        }
    }

    fun serializeTree(tree: ChatTree): String {
        val obj = JSONObject()
        val nodesObj = JSONObject()
        for ((id, node) in tree.nodes) {
            val nObj = JSONObject()
            nObj.put("text", node.text)
            nObj.put("isUser", node.isUser)
            nObj.put("parentId", node.parentId)
            nObj.put("lastActiveChildId", node.lastActiveChildId)
            nObj.put("modelName", node.modelName)
            nObj.put("giftKey", node.giftKey)
            nObj.put("suggestionA", node.suggestionA)
            nObj.put("suggestionB", node.suggestionB)
            nObj.put("suggestionC", node.suggestionC)
            if (node.giftRequestYen != null) nObj.put("giftRequestYen", node.giftRequestYen) else nObj.put("giftRequestYen", JSONObject.NULL)
            val childrenArray = JSONArray()
            node.childrenIds.forEach { childrenArray.put(it) }
            nObj.put("childrenIds", childrenArray)
            nodesObj.put(id, nObj)
        }
        obj.put("nodes", nodesObj)
        obj.put("currentNodeId", tree.currentNodeId)
        return obj.toString()
    }

    fun deserializeTree(jsonStr: String): ChatTree {
        return try {
            if (jsonStr.startsWith("{")) {
                val obj = JSONObject(jsonStr)
                val nodesMap = mutableMapOf<String, ChatNode>()
                val nodesObj = obj.optJSONObject("nodes") ?: JSONObject()
                nodesObj.keys().forEach { key ->
                    val nObj = nodesObj.getJSONObject(key)
                    val childrenArray = nObj.optJSONArray("childrenIds") ?: JSONArray()
                    val childrenIds = mutableListOf<String>()
                    for (i in 0 until childrenArray.length()) childrenIds.add(childrenArray.getString(i))
                    nodesMap[key] = ChatNode(
                        id = key,
                        text = nObj.optString("text", ""),
                        isUser = nObj.optBoolean("isUser", false),
                        parentId = if (nObj.isNull("parentId")) null else nObj.optString("parentId", null),
                        childrenIds = childrenIds,
                        lastActiveChildId = if (nObj.isNull("lastActiveChildId")) null else nObj.optString("lastActiveChildId", null),
                        modelName = if (nObj.isNull("modelName")) null else nObj.optString("modelName", null),
                        giftKey = if (nObj.isNull("giftKey")) null else nObj.optString("giftKey", null),
                        suggestionA = if (nObj.isNull("suggestionA")) null else nObj.optString("suggestionA", null),
                        suggestionB = if (nObj.isNull("suggestionB")) null else nObj.optString("suggestionB", null),
                        suggestionC = if (nObj.isNull("suggestionC")) null else nObj.optString("suggestionC", null),
                        giftRequestYen = if (nObj.isNull("giftRequestYen")) null else nObj.optInt("giftRequestYen").takeIf { it > 0 }
                    )
                }
                val currentNodeId = if (obj.isNull("currentNodeId")) null else obj.optString("currentNodeId", null)
                ChatTree(nodesMap, currentNodeId)
            } else {
                val array = JSONArray(jsonStr)
                val nodesMap = mutableMapOf<String, ChatNode>()
                var prevId: String? = null
                var lastId: String? = null
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val id = UUID.randomUUID().toString()
                    val node = ChatNode(
                        id = id,
                        text = item.optString("text", ""),
                        isUser = item.optBoolean("isUser", false),
                        parentId = prevId
                    )
                    nodesMap[id] = node
                    if (prevId != null) {
                        nodesMap[prevId]?.childrenIds?.add(id)
                        nodesMap[prevId]?.lastActiveChildId = id
                    }
                    prevId = id
                    lastId = id
                }
                ChatTree(nodesMap, lastId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize chat tree", e)
            ChatTree(mutableMapOf(), null)
        }
    }

    private fun sessionsDir(context: Context): File {
        return File(context.filesDir, DIR_NAME).also { if (!it.exists()) it.mkdirs() }
    }

    private fun sessionFile(context: Context, sessionId: String): File {
        return File(sessionsDir(context), "$sessionId.json")
    }

    private fun indexFile(context: Context): File {
        return File(sessionsDir(context), INDEX_FILE)
    }

    private fun linksFile(context: Context): File {
        return File(sessionsDir(context), LINKS_FILE)
    }

    private fun loadIndex(context: Context): List<Pair<String, String>> {
        val fileJson = AtomicFiles.readUtf8(indexFile(context))
        if (!fileJson.isNullOrBlank()) {
            return parseIndex(fileJson)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val list = parseIndex(json)
        if (list.isNotEmpty()) {
            saveIndex(context, list)
        }
        return list
    }

    private fun saveIndex(context: Context, list: List<Pair<String, String>>) {
        val array = JSONArray()
        list.forEach { (id, name) ->
            array.put(JSONObject().apply {
                put("id", id)
                put("name", name)
            })
        }
        AtomicFiles.writeUtf8(indexFile(context), array.toString())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SESSIONS, array.toString())
            .apply()
    }

    private fun parseIndex(json: String): List<Pair<String, String>> {
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(obj.getString("id") to obj.getString("name"))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse session index", e)
            emptyList()
        }
    }

    private fun loadLinksLocked(context: Context): MutableMap<String, String> {
        linksCache?.let { return it }
        val map = mutableMapOf<String, String>()
        val json = AtomicFiles.readUtf8(linksFile(context))
        if (!json.isNullOrBlank()) {
            try {
                val obj = JSONObject(json)
                obj.keys().forEach { key ->
                    map[key] = obj.optString(key, "")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse image chat links", e)
            }
        }
        linksCache = map
        return map
    }

    private fun saveLinksLocked(context: Context, links: MutableMap<String, String>) {
        linksCache = links
        val obj = JSONObject()
        links.forEach { (uri, chatId) -> obj.put(uri, chatId) }
        AtomicFiles.writeUtf8(linksFile(context), obj.toString())
    }
}
