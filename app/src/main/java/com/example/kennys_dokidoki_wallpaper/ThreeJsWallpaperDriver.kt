package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * Offscreen WebGL driver that renders bundled Three.js works into Bitmap frames.
 *
 *   WebView (player.html + three.min.js)
 *        |  GPU WebGL
 *        v
 *   ThreeJsPresentation ---> VirtualDisplay ---> ImageReader ---> Bitmap frame
 */
class ThreeJsWallpaperDriver(
    private val appContext: Context,
    private val onFrameReady: () -> Unit
) {
    companion object {
        private const val TAG = "ThreeJsWallpaperDriver"
        private const val DISPLAY_NAME = "KennysThreeJsWallpaper"
        private const val BRIDGE_NAME = "AndroidThreeBridge"
        private const val HTTPS_SCHEME = "https"
        private const val UTF8 = "UTF-8"
        private const val BLANK_URL = "about:blank"
        private const val MAX_QUEUE_IMAGES = 4
        private const val PIXEL_BYTES_RGBA = 4
        private const val HTTP_NOT_FOUND = 404
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: ThreeJsPresentation? = null
    private var webView: WebView? = null

    private var heldHardwareImage: Image? = null
    private val softwareBuffers = arrayOfNulls<Bitmap>(2)
    private var softwareIndex = 0
    private var paddedScratchBitmap: Bitmap? = null

    private var latestFrame: Bitmap? = null
    private var activeWorkId: String? = null
    private var renderWidth = 0
    private var renderHeight = 0
    private var running = false
    private var pageReady = false

    fun currentFrame(): Bitmap? {
        return latestFrame
    }

    fun currentWorkId(): String? {
        return activeWorkId
    }

    // Start or retarget the Three.js pipeline to display the requested built-in work.
    fun showWork(workId: String, surfaceWidth: Int, surfaceHeight: Int) {
        val work = ThreeJsCatalog.workOrDefault(workId)
        val dims = ThreeJsPolicy.computeRenderSize(surfaceWidth, surfaceHeight)
        activeWorkId = work.id

        val sizeChanged = dims.width != renderWidth || dims.height != renderHeight
        if (!running || sizeChanged) {
            rebuildPipeline(dims.width, dims.height)
            return
        }

        if (pageReady) {
            dispatchWorkToJs(work.id)
        }
    }

    fun onSurfaceResize(surfaceWidth: Int, surfaceHeight: Int) {
        val workId = activeWorkId ?: return
        if (!running) {
            return
        }

        val dims = ThreeJsPolicy.computeRenderSize(surfaceWidth, surfaceHeight)
        if (dims.width != renderWidth || dims.height != renderHeight) {
            showWork(workId, surfaceWidth, surfaceHeight)
        }
    }

    // Tear down WebView, Presentation, VirtualDisplay, and ImageReader so idle power is zero.
    fun stop() {
        running = false
        pageReady = false
        latestFrame = null
        renderWidth = 0
        renderHeight = 0

        imageReader?.setOnImageAvailableListener(null, null)

        webView?.let { view ->
            try {
                view.evaluateJavascript(
                    "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.stop();",
                    null
                )
                view.stopLoading()
                view.onPause()
                view.loadUrl(BLANK_URL)
            } catch (t: Throwable) {
                Log.w(TAG, "WebView pause failed", t)
            }
        }

        try {
            presentation?.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "Presentation dismiss failed", t)
        }
        presentation = null

        webView?.let { view ->
            try {
                (view.parent as? ViewGroup)?.removeView(view)
                view.removeAllViews()
                view.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "WebView destroy failed", t)
            }
        }
        webView = null

        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay release failed", t)
        }
        virtualDisplay = null

        closeQuietly(heldHardwareImage)
        heldHardwareImage = null

        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader close failed", t)
        }
        imageReader = null

        softwareBuffers[0] = null
        softwareBuffers[1] = null
        paddedScratchBitmap = null
    }

    private fun rebuildPipeline(width: Int, height: Int) {
        val targetWork = activeWorkId
        stop()
        activeWorkId = targetWork
        renderWidth = width
        renderHeight = height

        val reader = createImageReader(width, height) ?: return
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> consumeFrame(r) }, mainHandler)

        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager == null) {
            stop()
            return
        }

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val vd = try {
            displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                DisplayMetrics.DENSITY_MEDIUM,
                reader.surface,
                flags
            )
        } catch (t: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", t)
            stop()
            return
        }

        if (vd == null || vd.display == null) {
            stop()
            return
        }
        virtualDisplay = vd
        running = true

        try {
            val pres = ThreeJsPresentation(appContext, vd.display)
            presentation = pres
            pres.show()
        } catch (t: Throwable) {
            Log.e(TAG, "Presentation show failed", t)
            stop()
        }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_CPU_READ_RARELY
                return ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    MAX_QUEUE_IMAGES,
                    usage
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Hardware ImageReader unavailable, falling back to CPU reader", t)
            }
        }

        return try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_QUEUE_IMAGES)
        } catch (t: Throwable) {
            Log.e(TAG, "Software ImageReader creation failed", t)
            null
        }
    }

    private fun consumeFrame(reader: ImageReader) {
        if (!running) {
            val stale = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            }
            closeQuietly(stale)
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        val hwBitmap = wrapHardwareBitmap(image)
        if (hwBitmap != null) {
            val previousHeld = heldHardwareImage
            heldHardwareImage = image
            latestFrame = hwBitmap
            closeQuietly(previousHeld)
            onFrameReady()
            return
        }

        val swBitmap = copySoftwareBitmap(image)
        closeQuietly(image)
        if (swBitmap != null) {
            latestFrame = swBitmap
            onFrameReady()
        }
    }

    private fun wrapHardwareBitmap(image: Image): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        val hwBuffer = try {
            image.hardwareBuffer
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            Bitmap.wrapHardwareBuffer(hwBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
        } catch (_: Throwable) {
            null
        } finally {
            try {
                hwBuffer.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun copySoftwareBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) {
                return null
            }

            val buffer = plane.buffer
            buffer.rewind()

            val pixelStride = plane.pixelStride.takeIf { it > 0 } ?: PIXEL_BYTES_RGBA
            val rowStride = plane.rowStride
            val nextSlot = (softwareIndex + 1) % softwareBuffers.size
            val target = ensureMutableBitmap(softwareBuffers[nextSlot], width, height)
            softwareBuffers[nextSlot] = target
            softwareIndex = nextSlot

            if (rowStride == width * pixelStride) {
                target.copyPixelsFromBuffer(buffer)
                return target
            }

            val paddedWidth = rowStride / pixelStride
            if (paddedWidth < width) {
                return null
            }

            val scratch = ensureMutableBitmap(paddedScratchBitmap, paddedWidth, height)
            paddedScratchBitmap = scratch
            scratch.copyPixelsFromBuffer(buffer)

            val rect = Rect(0, 0, width, height)
            Canvas(target).drawBitmap(scratch, rect, rect, null)
            target
        } catch (t: Throwable) {
            Log.w(TAG, "Software frame copy failed", t)
            null
        }
    }

    private fun ensureMutableBitmap(existing: Bitmap?, width: Int, height: Int): Bitmap {
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun closeQuietly(image: Image?) {
        if (image == null) {
            return
        }
        try {
            image.close()
        } catch (_: Throwable) {
        }
    }

    private fun onJsPlayerReady() {
        if (!running || pageReady) {
            return
        }
        pageReady = true
        val workId = activeWorkId ?: return
        dispatchWorkToJs(workId)
    }

    private fun dispatchWorkToJs(workId: String) {
        val safeId = workId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safeId.isEmpty()) {
            return
        }
        webView?.evaluateJavascript(
            "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.loadWork('$safeId');",
            null
        )
    }

    private fun interceptAssetRequest(request: WebResourceRequest): WebResourceResponse? {
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

        val assetPath = "${ThreeJsCatalog.ASSET_DIR}/$relPath"
        return try {
            val stream = appContext.assets.open(assetPath)
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

    private inner class JsBridge {
        @JavascriptInterface
        fun onPlayerReady() {
            mainHandler.post { onJsPlayerReady() }
        }
    }

    private inner class ThreeJsPresentation(
        outerContext: Context,
        display: Display
    ) : Presentation(outerContext, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.apply {
                val flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                setFlags(flags, flags)
                setWindowAnimations(0)
                setBackgroundDrawable(ColorDrawable(Color.BLACK))
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val view = WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }

                addJavascriptInterface(JsBridge(), BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        if (request == null) {
                            return notFoundResponse()
                        }
                        return interceptAssetRequest(request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onJsPlayerReady()
                    }
                }
            }

            setContentView(view)
            webView = view
            view.onResume()
            view.resumeTimers()
            view.loadUrl(ThreeJsCatalog.PLAYER_URL)
        }
    }
}
