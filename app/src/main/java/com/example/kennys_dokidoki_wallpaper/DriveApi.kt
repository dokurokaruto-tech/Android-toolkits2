package com.example.kennys_dokidoki_wallpaper

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Drive 上の1ファイル。更新時刻はエポックミリ秒に直して持つ */
data class DriveFile(val id: String, val name: String, val modifiedAtMs: Long)

/**
 * Google Drive REST v3 の薄いラッパー。HTTP の細かい話はここで閉じる。
 * 呼び出し側はフォルダ名とファイル名だけを知っていればよい。
 */
object DriveApi {

    private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val BOUNDARY = "kennysDiaryBoundary"
    private const val TIMEOUT_MS = 30_000

    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299

    /** フォルダを探し、無ければ作る。戻り値はフォルダID */
    fun ensureFolder(token: String, name: String): String {
        val query = "name = '$name' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = get(token, "$FILES_URL?q=${encode(query)}&fields=files(id,name)")
        val files = JSONObject(found).optJSONArray("files") ?: JSONArray()
        if (files.length() > 0) {
            return files.getJSONObject(0).getString("id")
        }
        val body = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(postJson(token, FILES_URL, body)).getString("id")
    }

    fun listFolder(token: String, folderId: String): List<DriveFile> {
        val query = "'$folderId' in parents and trashed = false"
        val raw = get(token, "$FILES_URL?q=${encode(query)}&fields=files(id,name,modifiedTime)&pageSize=1000")
        val files = JSONObject(raw).optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { index ->
            val item = files.getJSONObject(index)
            DriveFile(
                id = item.getString("id"),
                name = item.getString("name"),
                modifiedAtMs = DriveTimes.toEpochMs(item.optString("modifiedTime"))
            )
        }
    }

    /** 同名があれば中身を差し替え、無ければ新規に置く */
    fun upload(token: String, folderId: String, name: String, mimeType: String, bytes: ByteArray, existingId: String?): String {
        if (existingId != null) {
            patchMedia(token, existingId, mimeType, bytes)
            return existingId
        }
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .toString()
        val body = multipartBody(metadata, mimeType, bytes)
        val raw = request(
            token = token,
            url = "$UPLOAD_URL?uploadType=multipart&fields=id",
            method = "POST",
            contentType = "multipart/related; boundary=$BOUNDARY",
            body = body
        )
        return JSONObject(raw).getString("id")
    }

    fun download(token: String, fileId: String, target: File) {
        val connection = open("$FILES_URL/$fileId?alt=media", "GET", token)
        connection.inputStream.use { input ->
            target.parentFile?.mkdirs()
            target.outputStream().use { output -> input.copyTo(output) }
        }
        connection.disconnect()
    }

    private fun patchMedia(token: String, fileId: String, mimeType: String, bytes: ByteArray) {
        request(
            token = token,
            url = "$UPLOAD_URL/$fileId?uploadType=media&fields=id",
            method = "PATCH",
            contentType = mimeType,
            body = bytes
        )
    }

    private fun multipartBody(metadataJson: String, mimeType: String, bytes: ByteArray): ByteArray {
        val head = (
            "--$BOUNDARY\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                metadataJson + "\r\n" +
                "--$BOUNDARY\r\n" +
                "Content-Type: $mimeType\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$BOUNDARY--".toByteArray(Charsets.UTF_8)
        return head + bytes + tail
    }

    private fun get(token: String, url: String): String {
        val connection = open(url, "GET", token)
        return readBody(connection)
    }

    private fun postJson(token: String, url: String, body: String): String =
        request(token, url, "POST", "application/json; charset=UTF-8", body.toByteArray(Charsets.UTF_8))

    private fun request(token: String, url: String, method: String, contentType: String, body: ByteArray): String {
        val connection = open(url, method, token)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return readBody(connection)
    }

    private fun open(url: String, method: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // PATCH を素通ししない端末があるので、POST + オーバーライドで逃がす
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private fun readBody(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw IOException("Drive $code: $error")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return text
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
