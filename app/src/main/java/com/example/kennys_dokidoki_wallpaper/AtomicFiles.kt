package com.example.kennys_dokidoki_wallpaper

import java.io.File

/**
 * SharedPreferences の巨大XML書き込みに頼らず、アプリ専用ファイルへ原子的に保存するためのヘルパー。
 */
object AtomicFiles {
    fun writeUtf8(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (file.exists()) {
            file.delete()
        }
        if (!tmp.renameTo(file)) {
            file.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun readUtf8(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
