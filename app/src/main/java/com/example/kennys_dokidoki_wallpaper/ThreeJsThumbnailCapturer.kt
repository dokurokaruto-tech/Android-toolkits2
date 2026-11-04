package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.ArrayDeque

object ThreeJsAssetHost {
    private const val HTTPS_SCHEME = "https"
    private const val UTF8 = "UTF-8"
    private const val HTTP_NOT_FOUND = 404
    private const val WORKS_PREFIX = "works/"

    fun interceptRequest(
        context: Context,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val uri = request.url ?: return notFoundResponse()
        if (uri.scheme == "data") {
            return null
        }

        val isLocalAsset = uri.scheme == HTTPS_SCHEME && uri.host == ThreeJsCatalog.VIRTUAL_HOST
        if (!isLocalAsset) {
            return notFoundResponse()
        }

        val relPath = (uri.path ?: "").trimStart('/')
        if (relPath.isEmpty() || relPath.contains("..")) {
            return notFoundResponse()
        }

        if (relPath.startsWith(WORKS_PREFIX)) {
            val workFile = relPath.removePrefix(WORKS_PREFIX)
            val workStream = ThreeJsWorkStore.openWorkStream(context, workFile)
                ?: return notFoundResponse()
            return WebResourceResponse("application/json", UTF8, workStream)
        }

        val assetPath = "${ThreeJsCatalog.ASSET_DIR}/$relPath"
        return try {
            val stream = context.assets.open(assetPath)
            WebResourceResponse(mimeTypeFor(relPath), UTF8, stream)
        } catch (_: Throwable) {
            notFoundResponse()
        }
    }

    private fun mimeTypeFor(path: String): String {
        return when {
            path.endsWith(".html", ignoreCase = true) -> "text/html"
            path.endsWith(".js", ignoreCase = true) -> "application/javascript"
            path.endsWith(".json", ignoreCase = true) -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            UTF8,
            HTTP_NOT_FOUND,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }
}

class ThreeJsThumbnailCapturer(
    private val context: Context,
    private val hostContainer: ViewGroup,
    private val onThumbSaved: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingIds = ArrayDeque<String>()
    private var webView: WebView? = null
    private var isBridgeReady = false
    private var inFlightWorkId: String? = null
    private var released = false

    private val captureTimeoutTask = Runnable {
        val timedOut = inFlightWorkId ?: return@Runnable
        inFlightWorkId = null
        pumpQueue()
        if (timedOut.isNotEmpty() && pendingIds.isEmpty()) {
            teardownWebView()
        }
    }

    fun enqueueMissing(works: List<ThreeJsWork>) {
        if (released) {
            return
        }
        var added = false
        for (work in works) {
            val file = ThreeJsWorkStore.thumbnailFile(context, work.id)
            if (file.isFile && file.length() > 0L) {
                continue
            }
            if (work.id != inFlightWorkId && !pendingIds.contains(work.id)) {
                pendingIds.addLast(work.id)
                added = true
            }
        }
        if (added) {
            ensureWebViewAndPump()
        }
    }

    fun enqueueSingle(workId: String) {
        if (released || workId.isBlank()) {
            return
        }
        if (workId != inFlightWorkId && !pendingIds.contains(workId)) {
            pendingIds.addLast(workId)
        }
        ensureWebViewAndPump()
    }

    fun release() {
        released = true
        pendingIds.clear()
        inFlightWorkId = null
        mainHandler.removeCallbacksAndMessages(null)
        teardownWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebViewAndPump() {
        if (released) {
            return
        }
        if (webView != null) {
            pumpQueue()
            return
        }

        val view = runCatching { WebView(context) }.getOrNull() ?: return
        view.layoutParams = ViewGroup.LayoutParams(OFFSCREEN_VIEW_PX, OFFSCREEN_VIEW_PX)
        view.alpha = 0.01f
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.setBackgroundColor(Color.BLACK)

        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = false

        view.addJavascriptInterface(ThumbJsBridge(), JS_THUMB_BRIDGE_NAME)
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return ThreeJsAssetHost.interceptRequest(context.applicationContext, request)
            }
        }

        hostContainer.addView(view)
        webView = view
        isBridgeReady = false
        view.loadUrl(ThreeJsCatalog.CAPTURE_URL)
    }

    private fun pumpQueue() {
        if (released || !isBridgeReady || inFlightWorkId != null) {
            return
        }
        val nextId = pendingIds.pollFirst()
        if (nextId == null) {
            teardownWebView()
            return
        }
        val safeId = nextId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safeId.isEmpty()) {
            pumpQueue()
            return
        }
        inFlightWorkId = safeId
        mainHandler.removeCallbacks(captureTimeoutTask)
        mainHandler.postDelayed(captureTimeoutTask, CAPTURE_TIMEOUT_MS)
        webView?.evaluateJavascript(
            "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.captureWorkThumbnail('$safeId');",
            null
        )
    }

    private fun handleCaptured(workId: String, dataUrl: String) {
        if (released) {
            return
        }
        mainHandler.removeCallbacks(captureTimeoutTask)
        if (inFlightWorkId == workId) {
            inFlightWorkId = null
        }
        val saved = ThreeJsWorkStore.saveThumbnailDataUrl(context.applicationContext, workId, dataUrl)
        if (saved) {
            onThumbSaved(workId)
        }
        pumpQueue()
    }

    private fun handleCaptureFailed(workId: String) {
        if (released) {
            return
        }
        mainHandler.removeCallbacks(captureTimeoutTask)
        if (inFlightWorkId == workId) {
            inFlightWorkId = null
        }
        pumpQueue()
    }

    private fun teardownWebView() {
        val view = webView ?: return
        webView = null
        isBridgeReady = false
        runCatching {
            view.stopLoading()
            view.removeJavascriptInterface(JS_THUMB_BRIDGE_NAME)
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    }

    private inner class ThumbJsBridge {
        @JavascriptInterface
        fun onCapturerReady() {
            mainHandler.post {
                if (!released) {
                    isBridgeReady = true
                    pumpQueue()
                }
            }
        }

        @JavascriptInterface
        fun onThumbCaptured(workId: String, dataUrl: String) {
            mainHandler.post { handleCaptured(workId, dataUrl) }
        }

        @JavascriptInterface
        fun onThumbFailed(workId: String, _reason: String) {
            mainHandler.post { handleCaptureFailed(workId) }
        }
    }

    companion object {
        private const val JS_THUMB_BRIDGE_NAME = "AndroidThreeThumbBridge"
        private const val OFFSCREEN_VIEW_PX = 2
        private const val CAPTURE_TIMEOUT_MS = 4500L
    }
}
