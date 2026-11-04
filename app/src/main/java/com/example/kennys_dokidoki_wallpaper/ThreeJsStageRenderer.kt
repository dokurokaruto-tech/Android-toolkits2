package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * three.js 作品をオフスクリーン仮想ディスプレイで描き、フレームを Bitmap で壁紙へ渡す。
 *
 *   WebView(three.js) ─> VirtualDisplay ─> ImageReader ─> Bitmap.wrapHardwareBuffer
 *        ─> Listener.onFrame ─> Engine.draw()（既存のクロスフェード/テキスト描画に合流）
 *
 * ジェスチャーは Engine が onTouchEvent で処理するため、WebView にはタッチを渡さない。
 * 非表示になったら release() でディスプレイと WebView を丸ごと解放する（省電力思想）。
 */
class ThreeJsStageRenderer(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val handler: Handler,
    private val listener: Listener
) {

    interface Listener {
        fun onFrame(bitmap: Bitmap)
        fun onWorkReady(workId: String)
        fun onWorkError(workId: String, message: String)
    }

    private val appContext = context.applicationContext

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: ThreePresentation? = null
    private var webView: WebView? = null

    // GPU が読み終わる前にバッファが再利用されるのを防ぐため、直近2フレームを保持する。
    private var heldImage: Image? = null
    private var previousImage: Image? = null

    private var pageReady = false
    private var pendingWorkId: String? = null
    private var currentWorkId: String? = null
    private var released = false

    fun start() {
        if (released || imageReader != null) {
            return
        }
        if (width <= 0 || height <= 0) {
            listener.onWorkError("", "invalid surface size ${width}x$height")
            return
        }

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES, BUFFER_USAGE)
        reader.setOnImageAvailableListener(::onImageAvailable, handler)
        imageReader = reader

        val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.createVirtualDisplay(
            DISPLAY_NAME, width, height, DENSITY_DPI, reader.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        if (display == null) {
            listener.onWorkError("", "virtual display unavailable")
            release()
            return
        }
        virtualDisplay = display

        val present = ThreePresentation(appContext, display.display)
        presentation = present
        present.show()
    }

    fun loadWork(workId: String) {
        if (released) {
            return
        }
        currentWorkId = workId
        if (pageReady) {
            loadWorkNow(workId)
        } else {
            pendingWorkId = workId
        }
    }

    fun pause() {
        evaluate("window.ThreePlayer && ThreePlayer.pause();")
    }

    fun resume() {
        evaluate("window.ThreePlayer && ThreePlayer.resume();")
    }

    /**
     * 現在フレームをソフトウェア Bitmap に複製して返す。クロスフェードの残像用。
     * ImageReader のバッファ寿命から切り離すので、Engine が自由に保持・破棄できる。
     */
    fun snapshot(): Bitmap? {
        val buffer = heldImage?.hardwareBuffer ?: return null
        val hardware = Bitmap.wrapHardwareBuffer(buffer, SRGB) ?: return null
        return hardware.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun release() {
        if (released) {
            return
        }
        released = true

        webView?.let { view ->
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.loadUrl("about:blank")
            view.destroy()
        }
        webView = null

        presentation?.dismiss()
        presentation = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        previousImage?.close()
        previousImage = null
        heldImage?.close()
        heldImage = null
        imageReader?.close()
        imageReader = null
    }

    private fun loadWorkNow(workId: String) {
        evaluate("window.ThreePlayer && ThreePlayer.start('$workId');")
    }

    private fun evaluate(script: String) {
        val view = webView ?: return
        view.evaluateJavascript(script, null)
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (released) {
            return
        }
        val image = reader.acquireLatestImage() ?: return

        // 2フレーム前の Image を解放してバッファスロットを生産者へ返す。
        previousImage?.close()
        previousImage = heldImage
        heldImage = image

        val buffer = image.hardwareBuffer ?: return
        val bitmap = Bitmap.wrapHardwareBuffer(buffer, SRGB) ?: return
        listener.onFrame(bitmap)
    }

    @SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
    private inner class ThreePresentation(context: Context, display: Display) : Presentation(context, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            window?.setLayout(width, height)

            // 仮想ディスプレイのメトリクスを使うため Presentation のコンテキストで生成する。
            val view = WebView(this.context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = true
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.setSupportZoom(false)
            view.setBackgroundColor(Color.BLACK)
            view.isLongClickable = false
            view.setOnLongClickListener { true }
            view.addJavascriptInterface(Bridge(), BRIDGE_NAME)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageReady = true
                    pendingWorkId?.let { loadWorkNow(it) }
                }
            }

            setContentView(view, ViewGroup.LayoutParams(width, height))
            webView = view
            view.loadUrl(PLAYER_URL)
        }
    }

    private inner class Bridge {

        @JavascriptInterface
        fun workJson(id: String): String {
            val path = ThreeJsWork.byId(id)?.assetPath ?: "threejs/works/$id.json"
            return try {
                appContext.assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "workJson failed for $id", e)
                "{}"
            }
        }

        @JavascriptInterface
        fun onFirstFrame(id: String) {
            handler.post { listener.onWorkReady(id) }
        }

        @JavascriptInterface
        fun onError(message: String) {
            handler.post { listener.onWorkError(currentWorkId ?: "", message) }
        }
    }

    private companion object {
        const val TAG = "ThreeJsStageRenderer"
        const val DISPLAY_NAME = "ThreeJsWallpaper"
        const val PLAYER_URL = "file:///android_asset/threejs/player.html"
        const val BRIDGE_NAME = "ThreeJsNative"
        const val MAX_IMAGES = 3

        // 1dp = 1px にして、サーフェスの物理像素と WebView の CSS 像素を一致させる。
        const val DENSITY_DPI = 160

        const val BUFFER_USAGE = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
            HardwareBuffer.USAGE_CPU_READ_RARELY

        val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
}
