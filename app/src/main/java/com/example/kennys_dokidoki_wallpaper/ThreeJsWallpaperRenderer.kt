package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Hosts the bundled Three.js Editor player in a presentation on a virtual display
 * whose output is the WallpaperService surface. This keeps WebGL rendering off the
 * Canvas-only image path.
 *
 * Call all methods on the main thread.
 */
internal class ThreeJsWallpaperRenderer(
    private val context: Context,
    private val surface: Surface,
    private val width: Int,
    private val height: Int
) {
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var webView: WebView? = null

    fun matches(surfaceWidth: Int, surfaceHeight: Int): Boolean =
        surfaceWidth == width && surfaceHeight == height && webView != null && presentation?.isShowing == true

    @SuppressLint("SetJavaScriptEnabled")
    fun start(): Boolean {
        if (!surface.isValid || width <= 0 || height <= 0) return false

        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                context.resources.displayMetrics.densityDpi.coerceAtLeast(160),
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: return false
            virtualDisplay = display

            val secondaryDisplay = display.display
            val newPresentation = Presentation(context, secondaryDisplay)
            presentation = newPresentation

            val newWebView = WebView(newPresentation.context).apply {
                setBackgroundColor(Color.BLACK)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                }
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
                            Log.e(TAG, "Unable to load bundled Three.js wallpaper: ${error.description}")
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
            webView = newWebView

            newPresentation.window?.apply {
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
            }
            newPresentation.setContentView(newWebView)
            newPresentation.show()
            newWebView.loadUrl(ASSET_URL)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start the Three.js wallpaper renderer", error)
            release()
            false
        }
    }

    fun dispatchTouchEvent(event: MotionEvent) {
        val activePresentation = presentation ?: return
        val copy = MotionEvent.obtain(event)
        try {
            activePresentation.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    fun release() {
        val oldWebView = webView
        val oldPresentation = presentation
        val oldDisplay = virtualDisplay
        webView = null
        presentation = null
        virtualDisplay = null

        try {
            oldWebView?.stopLoading()
            if (oldPresentation?.isShowing == true) oldPresentation.dismiss()
            (oldWebView?.parent as? ViewGroup)?.removeView(oldWebView)
            oldWebView?.webChromeClient = null
            oldWebView?.webViewClient = null
            oldWebView?.destroy()
        } catch (error: Exception) {
            Log.w(TAG, "Error while releasing the Three.js wallpaper renderer", error)
        } finally {
            oldDisplay?.release()
        }
    }

    private companion object {
        const val TAG = "ThreeJsWallpaper"
        const val DISPLAY_NAME = "ThreeJsLiveWallpaper"
        const val ASSET_DIRECTORY = "/android_asset/wallpaper/threejs/r110/"
        const val ASSET_URL = "file:///android_asset/wallpaper/threejs/r110/index.html"
    }
}
