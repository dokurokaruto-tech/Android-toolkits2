package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

fun main() = runBlocking {
    val root = Files.createTempDirectory("original-transfer-check").toFile()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val requests = AtomicInteger()
    val data = ByteArray(128 * 1024) { (it % 251).toByte() }
    server.createContext("/api/v1/files/2026-10-16/test.png") { exchange ->
        requests.incrementAndGet()
        started.countDown()
        check(release.await(10, TimeUnit.SECONDS))
        exchange.sendResponseHeaders(200, data.size.toLong())
        exchange.responseBody.use { it.write(data) }
        exchange.close()
    }
    server.start()
    try {
        OriginalImageMemoryCache.initialize(Context(root))
        val uri = Uri.parse("http://127.0.0.1:${server.address.port}/api/v1/files/2026-10-16/test.png?token=old")
        val waiter = launch(Dispatchers.Default) { OriginalImageMemoryCache.getFile(uri) }
        check(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        waiter.cancelAndJoin() // Page change must not cancel the transfer.
        OriginalImageMemoryCache.clear() // Nor may heap pressure discard it.
        release.countDown()
        val file = withTimeout(5000) {
            OriginalImageMemoryCache.getFile(Uri.parse(uri.toString().replace("old", "new")))
        }
        check(file.readBytes().contentEquals(data))
        check(requests.get() == 1) // Token rotation joins the same download.
        server.stop(0)
        OriginalImageMemoryCache.clear()
        OriginalImageMemoryCache.initialize(Context(root))
        val offline = withTimeout(5000) { OriginalImageMemoryCache.getFile(uri) }
        check(offline.readBytes().contentEquals(data))
        check(requests.get() == 1)
        check(root.walk().none { it.name.endsWith(".part") })
        println("Transfer checks passed: cancellation, RAM trim, deduplication, offline reuse, atomic files")
    } finally {
        release.countDown()
        server.stop(0)
        root.deleteRecursively()
    }
}
