package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class FullScreenImageActivity : AppCompatActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var imageView: ProgressiveTileImageView
    private lateinit var tvCounter: TextView
    private var albumName: String = ""
    private var currentIndex: Int = 0
    private val currentEntries = mutableListOf<ImageEntry>()
    private var requestSerial = 0L
    private var activeTarget: CustomTarget<Drawable>? = null
    private var progressiveJob: Job? = null
    private var prefetchJob: Job? = null
    private var isClosing = false
    private var isGeneratedViewer = false
    private var isRemoteGenerated = false
    private lateinit var viewerChromeBar: View
    private lateinit var btnStartTempChat: View
    private lateinit var btnDeleteImage: View
    private val deletedUris = arrayListOf<String>()
    private val chromeHandler = Handler(Looper.getMainLooper())
    private var chromeVisible = true
    private var lastChromeInteractionMs = 0L
    private val hideChromeRunnable = Runnable { hideViewerChrome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_image)
        DataManager.loadData(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        rootLayout = findViewById(R.id.full_screen_root)
        imageView = findViewById(R.id.full_screen_image)
        tvCounter = findViewById(R.id.tv_image_counter)
        viewerChromeBar = findViewById(R.id.viewer_chrome_bar)
        btnStartTempChat = findViewById(R.id.btn_start_temp_chat)
        btnDeleteImage = findViewById(R.id.btn_delete_image)
        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        currentIndex = intent.getIntExtra("START_INDEX", 0)
        isGeneratedViewer = intent.getBooleanExtra("FROM_GENERATED_VIEWER", false) ||
            intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS") != null
        isRemoteGenerated = intent.getBooleanExtra("REMOTE_GENERATED", false)

        loadImages()
        showImage()
        setupViewerActions()

        val leftClick = View.OnClickListener {
            if (currentEntries.isNotEmpty()) {
                currentIndex = if (currentIndex > 0) currentIndex - 1 else currentEntries.lastIndex
                showImage()
            }
        }
        val rightClick = View.OnClickListener {
            if (currentEntries.isNotEmpty()) {
                currentIndex = if (currentIndex < currentEntries.lastIndex) currentIndex + 1 else 0
                showImage()
            }
        }
        findViewById<View>(R.id.zone_left).setOnClickListener(leftClick)
        findViewById<View>(R.id.zone_right).setOnClickListener(rightClick)
        findViewById<View>(R.id.blank_space_handler).setOnTouchListener { view, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (event.x < view.width / 2f) leftClick.onClick(view) else rightClick.onClick(view)
            }
            true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            revealViewerChrome()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupViewerActions() {
        if (!isGeneratedViewer) {
            btnStartTempChat.visibility = View.GONE
            btnDeleteImage.visibility = View.GONE
            return
        }
        btnStartTempChat.visibility = View.VISIBLE
        btnDeleteImage.visibility = View.VISIBLE
        btnStartTempChat.alpha = 1f
        btnDeleteImage.alpha = 1f
        btnStartTempChat.setOnClickListener {
            if (!chromeVisible) {
                revealViewerChrome()
                return@setOnClickListener
            }
            val entry = currentEntries.getOrNull(currentIndex) ?: return@setOnClickListener
            startActivity(Intent(this, ChatOverlayActivity::class.java).apply {
                putExtra("IMAGE_URI", entry.uri.toString())
                putExtra("GENERATED_TEMP_CHAT", true)
            })
        }
        btnDeleteImage.setOnClickListener {
            if (!chromeVisible) {
                revealViewerChrome()
                return@setOnClickListener
            }
            confirmDeleteCurrentImage()
        }
        revealViewerChrome()
    }

    private fun confirmDeleteCurrentImage() {
        val entry = currentEntries.getOrNull(currentIndex) ?: return
        val message = if (isRemoteGenerated || ImageStoragePolicy.isRemote(entry.uri)) {
            "この画像をPCからも削除します。紐づいた仮チャットも消えます。"
        } else {
            "この生成画像を削除します。紐づいた仮チャットも消えます。"
        }
        AlertDialog.Builder(this)
            .setTitle("画像の削除")
            .setMessage(message)
            .setNeutralButton("削除する") { _, _ -> deleteCurrentImage(entry) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteCurrentImage(entry: ImageEntry) {
        lifecycleScope.launch {
            val deleted = try {
                if (isRemoteGenerated || ImageStoragePolicy.isRemote(entry.uri)) {
                    GenerationAgentClient.deleteLibraryImage(this@FullScreenImageActivity, entry.uri)
                } else {
                    DataManager.deleteImageFile(this@FullScreenImageActivity, entry.uri)
                }
            } catch (_: Exception) {
                false
            }
            if (!deleted) {
                Toast.makeText(this@FullScreenImageActivity, "削除に失敗しました。", Toast.LENGTH_LONG).show()
                return@launch
            }
            GeneratedImageDraftStore.deleteImageAndMaybeChat(this@FullScreenImageActivity, entry.uri)
            DataManager.allImages.removeAll { it.uri.toString() == entry.uri.toString() }
            DataManager.saveData(this@FullScreenImageActivity)
            deletedUris.add(entry.uri.toString())
            val removedAt = currentIndex
            currentEntries.removeAt(removedAt)
            if (currentEntries.isEmpty()) {
                Toast.makeText(this@FullScreenImageActivity, "削除しました。", Toast.LENGTH_SHORT).show()
                closeViewerImmediately()
                return@launch
            }
            currentIndex = removedAt.coerceAtMost(currentEntries.lastIndex)
            Toast.makeText(this@FullScreenImageActivity, "削除しました。", Toast.LENGTH_SHORT).show()
            showImage()
        }
    }

    private fun revealViewerChrome() {
        lastChromeInteractionMs = System.currentTimeMillis()
        chromeHandler.removeCallbacks(hideChromeRunnable)
        if (!chromeVisible) {
            chromeVisible = true
            chromeViews().forEach { view ->
                view.visibility = View.VISIBLE
                view.animate().cancel()
                view.animate().alpha(1f).setDuration(220).start()
            }
        } else if (isGeneratedViewer) {
            btnStartTempChat.visibility = View.VISIBLE
            btnStartTempChat.alpha = 1f
            btnDeleteImage.visibility = View.VISIBLE
            btnDeleteImage.alpha = 1f
        }
        if (isGeneratedViewer) {
            chromeHandler.postDelayed(hideChromeRunnable, ViewerChromePolicy.HIDE_AFTER_MS)
        }
    }

    private fun hideViewerChrome() {
        if (!isGeneratedViewer || isClosing) return
        if (!ViewerChromePolicy.shouldHide(System.currentTimeMillis(), lastChromeInteractionMs)) {
            val remaining = ViewerChromePolicy.HIDE_AFTER_MS - (System.currentTimeMillis() - lastChromeInteractionMs)
            chromeHandler.postDelayed(hideChromeRunnable, remaining.coerceAtLeast(50L))
            return
        }
        chromeVisible = false
        chromeViews().forEach { view ->
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction {
                    if (!chromeVisible) view.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    private fun chromeViews(): List<View> {
        val views = mutableListOf(viewerChromeBar, tvCounter)
        if (isGeneratedViewer) {
            views.add(btnDeleteImage)
            views.add(btnStartTempChat)
        }
        return views
    }

    override fun onBackPressed() {
        closeViewerImmediately()
    }

    private fun closeViewerImmediately() {
        if (isClosing) return
        isClosing = true
        requestSerial++
        progressiveJob?.cancel()
        progressiveJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        activeTarget?.let { Glide.with(this).clear(it) }
        activeTarget = null
        imageView.prepareForLoad()
        setResult(RESULT_OK, Intent().apply {
            putExtra("FINAL_INDEX", currentIndex)
            if (deletedUris.isNotEmpty()) {
                putStringArrayListExtra("DELETED_URIS", ArrayList(deletedUris))
            }
        })
        // Calling finish directly avoids waiting on the back dispatcher while image/network
        // callbacks are being canceled, which caused intermittent apparent freezes.
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        progressiveJob?.cancel()
        prefetchJob?.cancel()
        activeTarget?.let { Glide.with(this).clear(it) }
        super.onDestroy()
    }

    private fun loadImages() {
        val virtualUris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS")
        if (virtualUris != null) {
            currentEntries.clear()
            virtualUris.forEach { currentEntries.add(ImageEntry(Uri.parse(it))) }
            return
        }

        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val isSortAscending = settingsPrefs.getBoolean("sort_ascending", true)
        if (albumName.isNotEmpty()) {
            DataManager.imageSetList.find { it.name == albumName }?.let { set ->
                val baseList = set.filterImages(DataManager.allImages)
                currentEntries.addAll(if (isSortAscending) baseList else baseList.reversed())
            }
        } else {
            val baseList = DataManager.allImages.toList()
            currentEntries.addAll(if (isSortAscending) baseList else baseList.reversed())
        }
        DataManager.pinCurrentWallpaperFirst(this, currentEntries)
    }

    private fun showImage() {
        if (isClosing || currentIndex !in currentEntries.indices) return
        val entry = currentEntries[currentIndex]
        val viewedIndex = currentIndex
        val serial = ++requestSerial
        progressiveJob?.cancel()
        progressiveJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        activeTarget?.let { Glide.with(this).clear(it) }
        activeTarget = null
        tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}"
        imageView.prepareForLoad()

        if (ImageStoragePolicy.isRemote(entry.uri)) {
            val cached = OriginalImageMemoryCache.getIfPresent(entry.uri)
            if (cached != null) {
                // A prefetched/cached original is already complete and can be shown immediately.
                displayOriginal(cached, entry.uri, serial)
            } else {
                progressiveJob = lifecycleScope.launch {
                    try {
                        ProgressiveOriginalLoader.load(
                            originalUri = entry.uri,
                            onDimensions = { width, height ->
                                if (serial == requestSerial) {
                                    applyImageRatio(width, height)
                                    imageView.beginProgressiveLoad(width, height)
                                }
                            },
                            onTile = { bitmap, top ->
                                if (serial == requestSerial) {
                                    imageView.appendDecodedTile(bitmap, top)
                                } else {
                                    bitmap.recycle()
                                }
                            }
                        )
                    } catch (progressiveError: Exception) {
                        if (progressiveError is CancellationException) throw progressiveError
                        // Older agents do not expose tile endpoints. Fall back to the complete
                        // original without manufacturing a reveal animation.
                        try {
                            val bytes = OriginalImageMemoryCache.getOrDownload(entry.uri)
                            if (serial == requestSerial) displayOriginal(bytes, entry.uri, serial)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            if (serial == requestSerial) {
                                Toast.makeText(
                                    this@FullScreenImageActivity,
                                    "オリジナル画像を読み込めません: ${error.message ?: progressiveError.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
            startStagedPrefetch(viewedIndex, entry.uri, serial)
        } else {
            displayOriginal(entry.uri, entry.uri, serial)
        }
    }

    /**
     * Download order for index 0 of 100 images:
     * current(0) -> pair(1, 99) -> pair(2, 98) -> stop.
     * Turning a page cancels this bounded plan and starts the same two-ring plan there.
     */
    private fun startStagedPrefetch(viewedIndex: Int, currentUri: Uri, serial: Long) {
        if (!ImageStoragePolicy.isRemote(currentUri)) return
        prefetchJob = lifecycleScope.launch {
            // Never start surrounding originals until the viewed original itself is complete.
            if (OriginalImageMemoryCache.getIfPresent(currentUri) == null) {
                try {
                    OriginalImageMemoryCache.getOrDownload(currentUri)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@launch
                }
            }
            if (serial != requestSerial || isClosing) return@launch

            for (distance in 1..2) {
                val candidates = surroundingUris(viewedIndex, distance)
                coroutineScope {
                    candidates.map { uri ->
                        async { OriginalImageMemoryCache.prefetch(uri) }
                    }.awaitAll()
                }
                if (serial != requestSerial || isClosing) return@launch
            }
            // Intentionally stop after two pairs. More pairs are admitted only by page turns.
        }
    }

    private fun surroundingUris(center: Int, distance: Int): List<Uri> {
        return CircularPrefetchPlanner.ring(center, currentEntries.size, distance)
            .map { currentEntries[it].uri }
            .filter { ImageStoragePolicy.isRemote(it) }
    }

    private fun applyImageRatio(width: Int, height: Int) {
        val set = ConstraintSet()
        set.clone(rootLayout)
        set.setDimensionRatio(R.id.full_screen_image, "$width:$height")
        set.applyTo(rootLayout)
    }

    private fun displayOriginal(model: Any, sourceUri: Uri, serial: Long) {
        activeTarget?.let { Glide.with(this).clear(it) }
        val target = object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (serial != requestSerial) return
                val width = resource.intrinsicWidth
                val height = resource.intrinsicHeight
                if (width > 0 && height > 0) applyImageRatio(width, height)
                imageView.showCompleteDrawable(resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                if (serial == requestSerial) imageView.showCompleteDrawable(placeholder)
            }
        }
        activeTarget = target
        Glide.with(this)
            .load(model)
            .override(Target.SIZE_ORIGINAL)
            .diskCacheStrategy(
                if (model is ByteArray) DiskCacheStrategy.NONE
                else ImageStoragePolicy.glideDiskCache(sourceUri, DiskCacheStrategy.RESOURCE)
            )
            .into(target)
    }
}
