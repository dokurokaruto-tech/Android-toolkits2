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
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
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
 *
 * Lifecycle invariants:
 * 1. Never create/destroy VirtualDisplay or Presentation during pause()/resume,
 *    because doing so mid-Recents-gesture stalls WindowManager / Quickstep.
 * 2. Never call WebView.onPause() or detach ImageReader's listener during pause():
 *    - WebView.onPause() tears down Chromium's Viz output surface on Presentation.
 *    - Detaching ImageReader's listener lets in-flight GPU frames fill all 4
 *      BufferQueue slots, deadlocking producer/consumer on resume.
 *    Instead, pause() stops the JS rAF/timer loop (0 FPS) while consumeFrame()
 *    drains any trailing in-flight frames so BufferQueue stays empty.
 */
class ThreeJsWallpaperDriver(
    private val appContext: Context,
    private val onFrameReady: () -> Unit
) {
    companion object {
        private const val TAG = "ThreeJsWallpaperDriver"
        private const val DISPLAY_NAME = "KennysThreeJsWallpaper"
        private const val BRIDGE_NAME = "AndroidThreeBridge"
        private const val UTF8 = "UTF-8"
        private const val BLANK_URL = "about:blank"
        private const val MAX_QUEUE_IMAGES = 4
        private const val MAX_DRAIN_ITERATIONS = 8
        private const val PIXEL_BYTES_RGBA = 4
        private const val HTTP_NOT_FOUND = 404
        private const val COLD_START_SETTLE_MS = 320L
        private const val RESIZE_DEBOUNCE_MS = 450L
        private const val FRAME_WATCHDOG_MS = 900L
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
    private var pendingResizeWidth = 0
    private var pendingResizeHeight = 0
    private var pipelineReady = false
    private var active = false
    private var pageReady = false

    private var coldBuildRunnable: Runnable? = null
    private var resizeRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null

    fun currentFrame(): Bitmap? {
        return latestFrame
    }

    fun currentWorkId(): String? {
        return activeWorkId
    }

    // Activate or resume the Three.js pipeline without recreating windows on resume.
    fun showWork(workId: String, surfaceWidth: Int, surfaceHeight: Int) {
        val work = ThreeJsWorkStore.workOrFirst(appContext, workId) ?: return
        val dims = ThreeJsPolicy.computeRenderSize(surfaceWidth, surfaceHeight)
        activeWorkId = work.id
        active = true

        if (!pipelineReady) {
            scheduleColdBuild(dims.width, dims.height)
            return
        }

        resumeExistingPipeline(work.id)
        if (dims.width != renderWidth || dims.height != renderHeight) {
            scheduleInPlaceResize(dims.width, dims.height)
        }
    }

    fun onSurfaceResize(surfaceWidth: Int, surfaceHeight: Int) {
        if (!active || !pipelineReady) {
            return
        }
        val dims = ThreeJsPolicy.computeRenderSize(surfaceWidth, surfaceHeight)
        if (dims.width != renderWidth || dims.height != renderHeight) {
            scheduleInPlaceResize(dims.width, dims.height)
        }
    }

    // Stop JS rendering and drop frame RAM while keeping ImageReader draining and WebView warm.
    fun pause() {
        active = false
        cancelPendingCallbacks()

        imageReader?.let { reader ->
            drainImageReader(reader)
        }
        releaseFrameBuffers()

        webView?.let { view ->
            try {
                view.evaluateJavascript(
                    "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.pause();",
                    null
                )
            } catch (t: Throwable) {
                Log.w(TAG, "JS pause evaluation failed", t)
            }
        }
    }

    // Full teardown when switching away from Three.js sets or destroying the Engine.
    fun stop() {
        active = false
        pipelineReady = false
        pageReady = false
        renderWidth = 0
        renderHeight = 0
        cancelPendingCallbacks()

        imageReader?.let { reader ->
            reader.setOnImageAvailableListener(null, null)
            drainImageReader(reader)
        }
        releaseFrameBuffers()

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
                Log.w(TAG, "WebView stop failed", t)
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

        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader close failed", t)
        }
        imageReader = null
    }

    private fun cancelPendingCallbacks() {
        coldBuildRunnable?.let { mainHandler.removeCallbacks(it) }
        coldBuildRunnable = null
        resizeRunnable?.let { mainHandler.removeCallbacks(it) }
        resizeRunnable = null
        disarmFrameWatchdog()
    }

    private fun scheduleColdBuild(width: Int, height: Int) {
        coldBuildRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            coldBuildRunnable = null
            if (!active || pipelineReady) {
                return@Runnable
            }
            buildPipeline(width, height)
        }
        coldBuildRunnable = task
        mainHandler.postDelayed(task, COLD_START_SETTLE_MS)
    }

    private fun scheduleInPlaceResize(width: Int, height: Int) {
        pendingResizeWidth = width
        pendingResizeHeight = height
        resizeRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            resizeRunnable = null
            if (!active || !pipelineReady) {
                return@Runnable
            }
            resizePipelineInPlace(pendingResizeWidth, pendingResizeHeight)
        }
        resizeRunnable = task
        mainHandler.postDelayed(task, RESIZE_DEBOUNCE_MS)
    }

    private fun armFrameWatchdog() {
        disarmFrameWatchdog()
        val task = Runnable {
            watchdogRunnable = null
            if (!active || latestFrame != null) {
                return@Runnable
            }
            val workId = activeWorkId ?: return@Runnable
            val width = renderWidth.takeIf { it > 0 } ?: return@Runnable
            val height = renderHeight.takeIf { it > 0 } ?: return@Runnable
            Log.w(TAG, "No frame received after resume; rebuilding Three.js pipeline")
            stop()
            activeWorkId = workId
            active = true
            buildPipeline(width, height)
        }
        watchdogRunnable = task
        mainHandler.postDelayed(task, FRAME_WATCHDOG_MS)
    }

    private fun disarmFrameWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun buildPipeline(width: Int, height: Int) {
        val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager == null) {
            return
        }

        val reader = createImageReader(width, height) ?: return
        reader.setOnImageAvailableListener({ r -> consumeFrame(r) }, mainHandler)

        val opened = openVirtualPresentation(displayManager, width, height, reader.surface)
        if (opened == null) {
            reader.setOnImageAvailableListener(null, null)
            try {
                reader.close()
            } catch (_: Throwable) {
            }
            return
        }

        imageReader = reader
        virtualDisplay = opened.first
        presentation = opened.second
        renderWidth = width
        renderHeight = height
        pipelineReady = true
    }

    private fun openVirtualPresentation(
        displayManager: DisplayManager,
        width: Int,
        height: Int,
        surface: Surface
    ): Pair<VirtualDisplay, ThreeJsPresentation>? {
        val privateOnlyFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val primary = tryShowPresentation(displayManager, width, height, surface, privateOnlyFlags)
        if (primary != null) {
            return primary
        }

        val fallbackFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        return tryShowPresentation(displayManager, width, height, surface, fallbackFlags)
    }

    private fun tryShowPresentation(
        displayManager: DisplayManager,
        width: Int,
        height: Int,
        surface: Surface,
        flags: Int
    ): Pair<VirtualDisplay, ThreeJsPresentation>? {
        val vd = try {
            displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                DisplayMetrics.DENSITY_MEDIUM,
                surface,
                flags
            )
        } catch (t: Throwable) {
            Log.w(TAG, "createVirtualDisplay failed for flags=$flags", t)
            null
        } ?: return null

        val targetDisplay = vd.display
        if (targetDisplay == null) {
            try {
                vd.release()
            } catch (_: Throwable) {
            }
            return null
        }

        return try {
            val pres = ThreeJsPresentation(appContext, targetDisplay)
            pres.show()
            vd to pres
        } catch (t: Throwable) {
            Log.w(TAG, "Presentation.show failed for flags=$flags", t)
            try {
                vd.release()
            } catch (_: Throwable) {
            }
            null
        }
    }

    private fun resumeExistingPipeline(workId: String) {
        imageReader?.let { reader ->
            drainImageReader(reader)
            reader.setOnImageAvailableListener({ r -> consumeFrame(r) }, mainHandler)
        }
        webView?.let { view ->
            try {
                view.onResume()
                view.invalidate()
            } catch (t: Throwable) {
                Log.w(TAG, "WebView invalidate failed", t)
            }
        }
        if (pageReady) {
            dispatchWorkToJs(workId)
        }
        armFrameWatchdog()
    }

    private fun resizePipelineInPlace(width: Int, height: Int) {
        val vd = virtualDisplay ?: return
        if (width == renderWidth && height == renderHeight) {
            return
        }

        val newReader = createImageReader(width, height) ?: return
        newReader.setOnImageAvailableListener({ r -> consumeFrame(r) }, mainHandler)
        val oldReader = imageReader
        oldReader?.setOnImageAvailableListener(null, null)

        try {
            vd.resize(width, height, DisplayMetrics.DENSITY_MEDIUM)
            vd.setSurface(newReader.surface)
        } catch (t: Throwable) {
            Log.w(TAG, "VirtualDisplay in-place resize failed", t)
            newReader.setOnImageAvailableListener(null, null)
            try {
                newReader.close()
            } catch (_: Throwable) {
            }
            oldReader?.setOnImageAvailableListener({ r -> consumeFrame(r) }, mainHandler)
            return
        }

        releaseFrameBuffers()
        if (oldReader != null) {
            drainImageReader(oldReader)
            try {
                oldReader.close()
            } catch (_: Throwable) {
            }
        }

        imageReader = newReader
        renderWidth = width
        renderHeight = height
        if (active) {
            webView?.invalidate()
            webView?.evaluateJavascript(
                "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.resize();",
                null
            )
        }
    }

    private fun drainImageReader(reader: ImageReader) {
        var count = 0
        while (count < MAX_DRAIN_ITERATIONS) {
            val next = try {
                reader.acquireNextImage()
            } catch (_: Throwable) {
                null
            } ?: break
            closeQuietly(next)
            count++
        }
    }

    private fun releaseFrameBuffers() {
        latestFrame = null
        closeQuietly(heldHardwareImage)
        heldHardwareImage = null
        softwareBuffers[0] = null
        softwareBuffers[1] = null
        paddedScratchBitmap = null
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
        if (!active) {
            drainImageReader(reader)
            return
        }

        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        disarmFrameWatchdog()

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
        if (!pipelineReady || pageReady) {
            return
        }
        pageReady = true
        if (!active) {
            return
        }
        val workId = activeWorkId ?: return
        dispatchWorkToJs(workId)
    }

    private fun handleRendererGone() {
        val resumeWork = activeWorkId
        val wasActive = active
        val width = renderWidth
        val height = renderHeight
        stop()
        if (wasActive && resumeWork != null && width > 0 && height > 0) {
            activeWorkId = resumeWork
            active = true
            scheduleColdBuild(width, height)
        }
    }

    private fun dispatchWorkToJs(workId: String) {
        val safeId = workId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safeId.isEmpty()) {
            return
        }
        webView?.evaluateJavascript(
            "window.ThreeWallpaperPlayer && window.ThreeWallpaperPlayer.activateWork('$safeId');",
            null
        )
    }

    private fun interceptAssetRequest(request: WebResourceRequest): WebResourceResponse? {
        return ThreeJsAssetHost.interceptRequest(appContext, request)
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

        init {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            window?.apply {
                val flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
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
                isFocusable = false
                isFocusableInTouchMode = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }

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

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        mainHandler.post { handleRendererGone() }
                        return true
                    }
                }
            }

            setContentView(view)
            webView = view
            view.onResume()
            view.loadUrl(ThreeJsCatalog.PLAYER_URL)
        }
    }
}
