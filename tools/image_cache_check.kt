package com.example.kennys_dokidoki_wallpaper

import java.io.ByteArrayInputStream
import java.nio.file.Files

fun main() {
    var checks = 0
    fun expect(value: Boolean) {
        check(value) { "Check ${checks + 1} failed" }
        checks++
    }
    val url = "http://pc/api/v1/files/2026-10-16/a.png?token=old"
    expect(OriginalDiskCache.key(url) == OriginalDiskCache.key(url.replace("old", "new")))
    expect(OriginalDiskCache.key(url) != OriginalDiskCache.key(url.replace("//pc/", "//other/")))
    expect(OriginalDiskCache.key("http://pc/image?id=1") != OriginalDiskCache.key("http://pc/image?id=2"))
    val dir = Files.createTempDirectory("original-cache-check").toFile()
    try {
        val cache = OriginalDiskCache(dir, 8)
        val first = cache.store(url, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 4)
        expect(first.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        expect(OriginalDiskCache(dir, 8).existing(url) != null)
        expect(runCatching { cache.store(url, ByteArrayInputStream(byteArrayOf(5)), 4) }.isFailure)
        expect(cache.existing(url)!!.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        expect(dir.listFiles()!!.none { it.name.endsWith(".part") })
        expect(runCatching { cache.store("http://pc/empty", ByteArrayInputStream(byteArrayOf()), 0) }.isFailure)
        expect(runCatching { cache.store("http://pc/huge", ByteArrayInputStream(byteArrayOf()), OriginalDiskCache.MAX_IMAGE_BYTES + 1) }.isFailure)
        first.setLastModified(1)
        val second = cache.store("http://pc/b", ByteArrayInputStream(byteArrayOf(5, 6, 7, 8)), 4)
        second.setLastModified(2)
        cache.store("http://pc/c", ByteArrayInputStream(byteArrayOf(9, 10, 11, 12)), 4)
        expect(cache.existing(url) == null)
        expect(cache.existing("http://pc/b") != null)
        expect(cache.existing("http://pc/c") != null)
        expect(!ImageMemoryPressurePolicy.shouldClearGlideDisk(ImageMemoryPressurePolicy.Band.CRITICAL))
        expect(!ImageMemoryPressurePolicy.shouldCancelDownloads(ImageMemoryPressurePolicy.Band.CRITICAL))
        println("$checks image cache checks passed")
    } finally {
        dir.deleteRecursively()
    }
}
