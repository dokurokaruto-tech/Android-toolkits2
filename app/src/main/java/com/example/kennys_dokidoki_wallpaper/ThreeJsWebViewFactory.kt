package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Only the app-owned project JSON is exposed to the bundled local player. */
class ThreeJsAssetsBridge(
    private val context: Context,
    private val projectAssetPath: String,
    private val previewMode: Boolean
) {
    @Volatile
    private var cachedProjectJson: String? = null

    @JavascriptInterface
    fun getProjectJsonLength(): Int = loadProjectJson().length

    @JavascriptInterface
    fun getProjectJsonChunk(start: Int, maxLength: Int): String {
        val source = loadProjectJson()
        val safeStart = start.coerceIn(0, source.length)
        val chunkLength = maxLength.coerceIn(1, MAX_CHUNK_CHARS)
        var end = (safeStart + chunkLength).coerceAtMost(source.length)
        if (end < source.length && end > safeStart &&
            Character.isHighSurrogate(source[end - 1]) && Character.isLowSurrogate(source[end])
        ) {
            end -= 1
        }
        // Prefix each bounded response with its next UTF-16 offset so JS can
        // continue correctly even when a Unicode pair needs a one-char adjustment.
        return "$end\n${source.substring(safeStart, end)}"
    }

    @JavascriptInterface
    fun isPreviewMode(): Boolean = previewMode

    @Synchronized
    private fun loadProjectJson(): String {
        cachedProjectJson?.let { return it }
        val loaded = try {
            context.assets.open(projectAssetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read Three.js project asset: $projectAssetPath", error)
            "{}"
        }
        cachedProjectJson = loaded
        return loaded
    }

    private companion object {
        const val TAG = "ThreeJsAssets"
        const val MAX_CHUNK_CHARS = 48 * 1024
    }
}

internal object ThreeJsWebViewFactory {
    private const val TAG = "ThreeJsWebView"
    private const val ASSET_DIRECTORY = "/android_asset/wallpaper/threejs/r110/"
    const val BRIDGE_NAME = "ThreeJsAssets"

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, artwork: ThreeJsArtwork, previewMode: Boolean): WebView =
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            addJavascriptInterface(
                ThreeJsAssetsBridge(context.applicationContext, artwork.projectAssetPath, previewMode),
                BRIDGE_NAME
            )
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url
                    val isBundledAsset = url.scheme == "file" &&
                        url.path?.startsWith(ASSET_DIRECTORY) == true
                    return !isBundledAsset
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) {
                        Log.e(TAG, "Unable to load Three.js player page: ${error.description}")
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "${message.messageLevel()}: ${message.message()}")
                    return true
                }
            }
        }
}
