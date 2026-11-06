package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ThreeJsWorkStore {
    private const val STORE_DIR_NAME = "threejs_store"
    private const val WORKS_DIR_NAME = "works"
    private const val THUMBS_DIR_NAME = "thumbs"
    private const val MANIFEST_FILE_NAME = "manifest.json"
    private const val THUMB_VERSION_FILE = ".thumb_ver"
    private const val THUMB_CACHE_VERSION = "2"
    private const val DATE_LABEL_PATTERN = "yy MM dd"
    private const val DATA_URI_BASE64_MARKER = ";base64,"
    private const val NO_MANIFEST_METRIC = -1L
    private val lock = Any()
    private var thumbVersionVerified = false
    private var cachedManifestStamp = NO_MANIFEST_METRIC
    private var cachedManifestSize = NO_MANIFEST_METRIC
    private var cachedWorks: List<ThreeJsWork>? = null
    private var cachedDeletedBuiltIns: Set<String>? = null

    fun loadWorks(context: Context): List<ThreeJsWork> {
        synchronized(lock) {
            ensureThumbVersionLocked(context)
            val cached = readCachedWorksLocked(context)
            if (cached != null) {
                return cached
            }
            return loadManifestLocked(context).works.toList()
        }
    }

    fun workCount(context: Context): Int {
        return loadWorks(context).size
    }

    fun evictMemoryCache() {
        synchronized(lock) {
            cachedWorks = null
            cachedDeletedBuiltIns = null
            cachedManifestStamp = NO_MANIFEST_METRIC
            cachedManifestSize = NO_MANIFEST_METRIC
        }
    }

    fun findWork(context: Context, workId: String?): ThreeJsWork? {
        if (workId.isNullOrBlank()) {
            return null
        }
        return loadWorks(context).firstOrNull { it.id == workId }
    }

    fun workOrFirst(context: Context, workId: String?): ThreeJsWork? {
        val works = loadWorks(context)
        if (works.isEmpty()) {
            return null
        }
        if (!workId.isNullOrBlank()) {
            val match = works.firstOrNull { it.id == workId }
            if (match != null) {
                return match
            }
        }
        return works.first()
    }

    fun openWorkStream(context: Context, rawWorkFile: String): InputStream? {
        val workId = sanitizeWorkId(rawWorkFile.removeSuffix(".json"))
        if (workId.isEmpty()) {
            return null
        }
        synchronized(lock) {
            val overrideFile = workJsonFile(context, workId)
            if (overrideFile.isFile) {
                return runCatching { FileInputStream(overrideFile) }.getOrNull()
            }
            val manifest = loadManifestLocked(context)
            val entry = manifest.works.firstOrNull { it.id == workId }
            val assetPath = entry?.assetPath ?: ThreeJsCatalog.findById(workId)?.assetPath
            if (assetPath.isNullOrBlank()) {
                return null
            }
            return runCatching { context.assets.open(assetPath) }.getOrNull()
        }
    }

    fun readWorkJsonText(context: Context, workId: String): String? {
        val stream = openWorkStream(context, workId) ?: return null
        return stream.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    fun addUserWork(
        context: Context,
        preferredTitle: String,
        rawJson: String
    ): Result<ThreeJsWork> {
        val inspection = ThreeJsInspectorPolicy.inspectJsonText(rawJson)
        if (!inspection.kind.isValidJson()) {
            return Result.failure(IllegalArgumentException(inspection.summaryText))
        }
        val now = System.currentTimeMillis()
        val id = generateWorkId(now)
        val cleanTitle = preferredTitle.trim()
            .ifEmpty { inspection.suggestedTitle }
            .ifEmpty { id }
        val newWork = ThreeJsWork(
            id = id,
            title = cleanTitle,
            assetPath = null,
            dateLabel = formatDateLabel(now),
            origin = ThreeJsWorkOrigin.USER,
            updatedAtMs = now
        )
        synchronized(lock) {
            val file = workJsonFile(context, id)
            file.parentFile?.mkdirs()
            file.writeText(rawJson, Charsets.UTF_8)
            val state = loadManifestLocked(context)
            state.works.add(newWork)
            saveManifestLocked(context, state)
        }
        return Result.success(newWork)
    }

    fun renameWork(
        context: Context,
        workId: String,
        newTitle: String
    ): ThreeJsWork? {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        synchronized(lock) {
            val state = loadManifestLocked(context)
            val index = state.works.indexOfFirst { it.id == workId }
            if (index < 0) {
                return null
            }
            val updated = state.works[index].copy(
                title = trimmed,
                updatedAtMs = System.currentTimeMillis()
            )
            state.works[index] = updated
            saveManifestLocked(context, state)
            return updated
        }
    }

    fun updateWorkJson(
        context: Context,
        workId: String,
        newJson: String
    ): Result<ThreeJsWork> {
        val inspection = ThreeJsInspectorPolicy.inspectJsonText(newJson)
        if (!inspection.kind.isValidJson()) {
            return Result.failure(IllegalArgumentException(inspection.summaryText))
        }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val state = loadManifestLocked(context)
            val index = state.works.indexOfFirst { it.id == workId }
            if (index < 0) {
                return Result.failure(IllegalArgumentException("対象の作品が見つかりません"))
            }
            val file = workJsonFile(context, workId)
            file.parentFile?.mkdirs()
            file.writeText(newJson, Charsets.UTF_8)
            thumbnailFile(context, workId).delete()

            val updated = state.works[index].copy(
                dateLabel = formatDateLabel(now),
                updatedAtMs = now
            )
            state.works[index] = updated
            saveManifestLocked(context, state)
            return Result.success(updated)
        }
    }

    fun deleteWork(context: Context, workId: String): Boolean {
        synchronized(lock) {
            val state = loadManifestLocked(context)
            val index = state.works.indexOfFirst { it.id == workId }
            if (index < 0) {
                return false
            }
            val removed = state.works.removeAt(index)
            if (removed.origin == ThreeJsWorkOrigin.BUILT_IN) {
                state.deletedBuiltIns.add(removed.id)
            }
            workJsonFile(context, workId).delete()
            thumbnailFile(context, workId).delete()
            saveManifestLocked(context, state)
            return true
        }
    }

    fun hasDeletedBuiltIns(context: Context): Boolean {
        synchronized(lock) {
            return loadManifestLocked(context).deletedBuiltIns.isNotEmpty()
        }
    }

    fun restoreBuiltIns(context: Context): Int {
        synchronized(lock) {
            val state = loadManifestLocked(context)
            if (state.deletedBuiltIns.isEmpty()) {
                return 0
            }
            var restored = 0
            val existingIds = state.works.map { it.id }.toMutableSet()
            for (builtIn in ThreeJsCatalog.BUILT_IN_WORKS) {
                if (builtIn.id !in existingIds) {
                    state.works.add(restored, builtIn)
                    existingIds.add(builtIn.id)
                    restored++
                }
            }
            state.deletedBuiltIns.clear()
            saveManifestLocked(context, state)
            return restored
        }
    }

    fun thumbnailFile(context: Context, workId: String): File {
        val safeId = sanitizeWorkId(workId).ifEmpty { "unknown" }
        val dir = File(storeDir(context), THUMBS_DIR_NAME)
        dir.mkdirs()
        return File(dir, "$safeId.jpg")
    }

    fun saveThumbnailDataUrl(
        context: Context,
        workId: String,
        dataUrl: String
    ): Boolean {
        val markerIdx = dataUrl.indexOf(DATA_URI_BASE64_MARKER)
        if (markerIdx < 0) {
            return false
        }
        val base64Part = dataUrl.substring(markerIdx + DATA_URI_BASE64_MARKER.length)
        val bytes = runCatching { Base64.decode(base64Part, Base64.DEFAULT) }.getOrNull()
            ?: return false
        if (bytes.isEmpty()) {
            return false
        }
        synchronized(lock) {
            val target = thumbnailFile(context, workId)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            return runCatching {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    target.writeBytes(bytes)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
        }
    }

    fun clearAllThumbnails(context: Context) {
        synchronized(lock) {
            val dir = File(storeDir(context), THUMBS_DIR_NAME)
            dir.listFiles()?.forEach { file ->
                if (file.name != THUMB_VERSION_FILE) {
                    file.delete()
                }
            }
        }
    }

    private fun ensureThumbVersionLocked(context: Context) {
        if (thumbVersionVerified) {
            return
        }
        val dir = File(storeDir(context), THUMBS_DIR_NAME)
        dir.mkdirs()
        val marker = File(dir, THUMB_VERSION_FILE)
        val current = runCatching {
            if (marker.isFile) {
                marker.readText(Charsets.UTF_8).trim()
            } else {
                ""
            }
        }.getOrDefault("")
        if (current == THUMB_CACHE_VERSION) {
            thumbVersionVerified = true
            return
        }
        dir.listFiles()?.forEach { file ->
            if (file.name != THUMB_VERSION_FILE) {
                file.delete()
            }
        }
        runCatching { marker.writeText(THUMB_CACHE_VERSION, Charsets.UTF_8) }
        thumbVersionVerified = true
    }

    private fun storeDir(context: Context): File {
        val dir = File(context.filesDir, STORE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun workJsonFile(context: Context, workId: String): File {
        val safeId = sanitizeWorkId(workId).ifEmpty { "unknown" }
        val dir = File(storeDir(context), WORKS_DIR_NAME)
        dir.mkdirs()
        return File(dir, "$safeId.json")
    }

    private fun manifestFile(context: Context): File {
        return File(storeDir(context), MANIFEST_FILE_NAME)
    }

    private fun sanitizeWorkId(raw: String): String {
        return raw.filter { ch ->
            (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '-'
        }
    }

    private fun generateWorkId(nowMs: Long): String {
        val shortRand = UUID.randomUUID().toString().replace("-", "").take(6)
        return "u_${nowMs}_$shortRand"
    }

    private fun formatDateLabel(epochMs: Long): String {
        return SimpleDateFormat(DATE_LABEL_PATTERN, Locale.US).format(Date(epochMs))
    }

    private fun readCachedWorksLocked(context: Context): List<ThreeJsWork>? {
        val works = cachedWorks ?: return null
        val file = manifestFile(context)
        if (!file.isFile) {
            return null
        }
        if (file.lastModified() != cachedManifestStamp || file.length() != cachedManifestSize) {
            return null
        }
        return works
    }

    private fun cacheManifestLocked(file: File, state: ManifestState) {
        cachedWorks = state.works.toList()
        cachedDeletedBuiltIns = state.deletedBuiltIns.toSet()
        cachedManifestStamp = if (file.isFile) {
            file.lastModified()
        } else {
            NO_MANIFEST_METRIC
        }
        cachedManifestSize = if (file.isFile) {
            file.length()
        } else {
            NO_MANIFEST_METRIC
        }
    }

    private fun loadManifestLocked(context: Context): ManifestState {
        val file = manifestFile(context)
        val cachedList = readCachedWorksLocked(context)
        val cachedDeleted = cachedDeletedBuiltIns
        if (cachedList != null && cachedDeleted != null) {
            return ManifestState(
                works = cachedList.toMutableList(),
                deletedBuiltIns = cachedDeleted.toMutableSet()
            )
        }
        val works = mutableListOf<ThreeJsWork>()
        val deletedBuiltIns = linkedSetOf<String>()
        var loadedValidManifest = false

        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val deletedArr = root.optJSONArray("deletedBuiltIns")
                if (deletedArr != null) {
                    for (i in 0 until deletedArr.length()) {
                        val id = deletedArr.optString(i)
                        if (id.isNotBlank()) {
                            deletedBuiltIns.add(id)
                        }
                    }
                }
                val worksArr = root.optJSONArray("works")
                if (worksArr != null) {
                    for (i in 0 until worksArr.length()) {
                        val obj = worksArr.optJSONObject(i) ?: continue
                        val id = sanitizeWorkId(obj.optString("id"))
                        if (id.isEmpty()) {
                            continue
                        }
                        val origin = ThreeJsWorkOrigin.fromStored(obj.optString("origin"))
                        val builtIn = ThreeJsCatalog.findById(id)
                        val assetPath = obj.optString("assetPath").ifBlank {
                            builtIn?.assetPath
                        }
                        if (origin == ThreeJsWorkOrigin.USER && !workJsonFile(context, id).isFile) {
                            continue
                        }
                        works.add(
                            ThreeJsWork(
                                id = id,
                                title = obj.optString("title").ifBlank { builtIn?.title ?: id },
                                assetPath = assetPath,
                                dateLabel = obj.optString("dateLabel").ifBlank {
                                    builtIn?.dateLabel.orEmpty()
                                },
                                origin = origin,
                                updatedAtMs = obj.optLong("updatedAtMs", 0L)
                            )
                        )
                    }
                }
                loadedValidManifest = true
            }
        }

        val existingIds = works.map { it.id }.toHashSet()
        var seededNew = false
        for (builtIn in ThreeJsCatalog.BUILT_IN_WORKS) {
            if (builtIn.id !in existingIds && builtIn.id !in deletedBuiltIns) {
                works.add(builtIn)
                existingIds.add(builtIn.id)
                seededNew = true
            }
        }

        val state = ManifestState(works = works, deletedBuiltIns = deletedBuiltIns)
        if (!loadedValidManifest || seededNew) {
            saveManifestLocked(context, state)
        } else {
            cacheManifestLocked(file, state)
        }
        return state
    }

    private fun saveManifestLocked(context: Context, state: ManifestState) {
        val root = JSONObject()
        val worksArr = JSONArray()
        for (work in state.works) {
            val item = JSONObject()
            item.put("id", work.id)
            item.put("title", work.title)
            if (!work.assetPath.isNullOrBlank()) {
                item.put("assetPath", work.assetPath)
            }
            item.put("dateLabel", work.dateLabel)
            item.put("origin", work.origin.name)
            item.put("updatedAtMs", work.updatedAtMs)
            worksArr.put(item)
        }
        val deletedArr = JSONArray()
        for (deletedId in state.deletedBuiltIns) {
            deletedArr.put(deletedId)
        }
        root.put("works", worksArr)
        root.put("deletedBuiltIns", deletedArr)

        val target = manifestFile(context)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
        cacheManifestLocked(target, state)
    }

    private data class ManifestState(
        val works: MutableList<ThreeJsWork>,
        val deletedBuiltIns: MutableSet<String>
    )
}
