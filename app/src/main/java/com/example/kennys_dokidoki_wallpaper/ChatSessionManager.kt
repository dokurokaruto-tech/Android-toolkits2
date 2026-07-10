package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    var suggestionC: String? = null
)

data class ChatTree(
    val nodes: MutableMap<String, ChatNode>,
    var currentNodeId: String?
)

object ChatSessionManager {
    private const val PREFS_NAME = "chat_sessions"
    private const val KEY_SESSIONS = "sessions_list"

    fun getAllSessions(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(obj.getString("id") to obj.getString("name"))
        }
        return list
    }

    fun getSessionName(context: Context, sessionId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("id") == sessionId) return obj.getString("name")
        }
        return null
    }

    fun createNewSession(context: Context, name: String): String {
        val id = UUID.randomUUID().toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        
        val newObj = JSONObject().apply {
            put("id", id)
            put("name", name)
        }
        array.put(newObj)
        
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
        return id
    }

    fun renameSession(context: Context, sessionId: String, newName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("id") == sessionId) {
                obj.put("name", newName)
                break
            }
        }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    fun deleteSession(context: Context, sessionId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SESSIONS, "[]") ?: "[]"
        val array = JSONArray(json)
        val newArray = JSONArray()
        
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("id") != sessionId) {
                newArray.put(obj)
            }
        }
        prefs.edit().putString(KEY_SESSIONS, newArray.toString()).apply()
        
        val historyPrefs = context.getSharedPreferences("chat_history_$sessionId", Context.MODE_PRIVATE)
        historyPrefs.edit().clear().apply()
    }

    // 以前のセーブデータを読み込みつつ、ツリー形式に変換する機能
    fun loadSessionData(context: Context, sessionId: String): ChatTree {
        val prefs = context.getSharedPreferences("chat_history_$sessionId", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("history", "[]") ?: "[]"
        
        if (jsonStr.startsWith("{")) {
            val obj = JSONObject(jsonStr)
            val nodesMap = mutableMapOf<String, ChatNode>()
            val nodesObj = obj.getJSONObject("nodes")
            nodesObj.keys().forEach { key ->
                val nObj = nodesObj.getJSONObject(key)
                val childrenArray = nObj.getJSONArray("childrenIds")
                val childrenIds = mutableListOf<String>()
                for (i in 0 until childrenArray.length()) childrenIds.add(childrenArray.getString(i))
                nodesMap[key] = ChatNode(
                    id = key,
                    text = nObj.getString("text"),
                    isUser = nObj.getBoolean("isUser"),
                    parentId = if (nObj.isNull("parentId")) null else nObj.getString("parentId"),
                    childrenIds = childrenIds,
                    lastActiveChildId = if (nObj.isNull("lastActiveChildId")) null else nObj.getString("lastActiveChildId"),
                    modelName = if (nObj.isNull("modelName")) null else nObj.getString("modelName"),
                    giftKey = if (nObj.isNull("giftKey")) null else nObj.getString("giftKey"),
                    suggestionA = if (nObj.isNull("suggestionA")) null else nObj.getString("suggestionA"),
                    suggestionB = if (nObj.isNull("suggestionB")) null else nObj.getString("suggestionB"),
                    suggestionC = if (nObj.isNull("suggestionC")) null else nObj.getString("suggestionC")
                )
            }
            val currentNodeId = if (obj.isNull("currentNodeId")) null else obj.getString("currentNodeId")
            return ChatTree(nodesMap, currentNodeId)
        } else {
            // 旧バージョンのフラットな配列データをツリーに変換
            val array = JSONArray(jsonStr)
            val nodesMap = mutableMapOf<String, ChatNode>()
            var prevId: String? = null
            var lastId: String? = null
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val id = UUID.randomUUID().toString()
                val node = ChatNode(
                    id = id,
                    text = item.getString("text"),
                    isUser = item.getBoolean("isUser"),
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
            return ChatTree(nodesMap, lastId)
        }
    }

    fun saveSessionData(context: Context, sessionId: String, tree: ChatTree) {
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
            val childrenArray = JSONArray()
            node.childrenIds.forEach { childrenArray.put(it) }
            nObj.put("childrenIds", childrenArray)
            nodesObj.put(id, nObj)
        }
        obj.put("nodes", nodesObj)
        obj.put("currentNodeId", tree.currentNodeId)
        
        val prefs = context.getSharedPreferences("chat_history_$sessionId", Context.MODE_PRIVATE)
        prefs.edit().putString("history", obj.toString()).apply()
    }
}