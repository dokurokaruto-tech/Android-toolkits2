package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
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
        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        currentIndex = intent.getIntExtra("START_INDEX", 0)

        loadImages()
        showImage()

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

    override fun onBackPressed() {
        val resultIntent = android.content.Intent().putExtra("FINAL_INDEX", currentIndex)
        setResult(RESULT_OK, resultIntent)
        super.onBackPressed()
    }

    override fun onDestroy() {
        progressiveJob?.cancel()
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
        if (currentIndex !in currentEntries.indices) return
        val entry = currentEntries[currentIndex]
        val serial = ++requestSerial
        progressiveJob?.cancel()
        progressiveJob = null
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
                // Keep a compressed original in the 50-entry session cache for revisits.
                lifecycleScope.launch { OriginalImageMemoryCache.prefetch(entry.uri) }
            }
            // Current image request is started first; only then queue the following two.
            prefetchNextTwo()
        } else {
            displayOriginal(entry.uri, entry.uri, serial)
            prefetchNextTwo()
        }
    }

    /** Starts memory-only downloads for the next two originals while the current one is viewed. */
    private fun prefetchNextTwo() {
        if (currentEntries.size <= 1) return
        val count = minOf(2, currentEntries.size - 1)
        for (offset in 1..count) {
            val next = currentEntries[(currentIndex + offset) % currentEntries.size].uri
            if (ImageStoragePolicy.isRemote(next)) {
                lifecycleScope.launch { OriginalImageMemoryCache.prefetch(next) }
            }
        }
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
