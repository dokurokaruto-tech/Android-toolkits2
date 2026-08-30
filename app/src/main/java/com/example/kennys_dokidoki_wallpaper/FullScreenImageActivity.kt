package com.example.kennys_dokidoki_wallpaper

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
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
    private var remoteDate: String = ""
    private lateinit var viewerChromeBar: View
    private lateinit var btnStartTempChat: View
    private lateinit var deleteHoldHost: View
    private lateinit var btnDeleteImage: View
    private lateinit var deleteHoldRing: HoldConfirmRingView
    private lateinit var deleteHoldCenterRing: HoldConfirmRingView
    private lateinit var btnCreatePreset: View
    private lateinit var btnReplayGeneration: View
    private val deletedUris = arrayListOf<String>()
    private val chromeHandler = Handler(Looper.getMainLooper())
    private var chromeVisible = true
    private var lastChromeInteractionMs = 0L
    private val hideChromeRunnable = Runnable { hideViewerChrome() }
    private var deleteHoldAnimator: ValueAnimator? = null
    private var deleteHoldActive = false
    private var deleteHoldDownX = 0f
    private var deleteHoldDownY = 0f
    private val confirmDeleteHoldRunnable = Runnable { completeDeleteHold() }

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
        deleteHoldHost = findViewById(R.id.delete_hold_host)
        btnDeleteImage = findViewById(R.id.btn_delete_image)
        deleteHoldRing = findViewById(R.id.delete_hold_ring)
        deleteHoldCenterRing = findViewById(R.id.delete_hold_center_ring)
        deleteHoldCenterRing.setStrokeWidthPx(5f * resources.displayMetrics.density)
        btnCreatePreset = findViewById(R.id.btn_create_preset_from_image)
        btnReplayGeneration = findViewById(R.id.btn_replay_generation)
        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        currentIndex = intent.getIntExtra("START_INDEX", 0)
        isGeneratedViewer = intent.getBooleanExtra("FROM_GENERATED_VIEWER", false) ||
            intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS") != null
        isRemoteGenerated = intent.getBooleanExtra("REMOTE_GENERATED", false)
        remoteDate = intent.getStringExtra("REMOTE_DATE").orEmpty()

        loadImages()
        showImage()
        setupViewerActions()
        setupPageTurnTouches()
        observeLiveLibrary()
    }

    override fun onStart() {
        super.onStart()
        if (isGeneratedViewer && isRemoteGenerated && GenerationPipExpandPolicy.shouldAutoOpen(remoteDate)) {
            GeneratedLibraryLiveUpdate.bind(this, remoteDate)
        }
    }

    override fun onStop() {
        if (isGeneratedViewer && isRemoteGenerated) {
            GeneratedLibraryLiveUpdate.unbind()
        }
        super.onStop()
    }

    private fun observeLiveLibrary() {
        if (!isGeneratedViewer || !isRemoteGenerated || !GenerationPipExpandPolicy.shouldAutoOpen(remoteDate)) return
        lifecycleScope.launch {
            GeneratedLibraryLiveUpdate.snapshot.collect { snapshot ->
                if (isClosing || snapshot.date != remoteDate) return@collect
                applyIncomingRemoteImages(snapshot.images)
            }
        }
    }

    private fun applyIncomingRemoteImages(incoming: List<AgentGeneratedImage>) {
        val existing = currentEntries.map { it.uri.toString() }
        val merged = GeneratedLibraryMergePolicy.prependNewUrls(existing, incoming.map { it.url })
        if (merged == existing) return
        val added = merged.size - existing.size
        val byUrl = incoming.associateBy { it.url }
        currentEntries.clear()
        merged.forEach { url ->
            val image = byUrl[url]
            currentEntries.add(
                GeneratedImageDraftStore.entryFor(
                    this,
                    Uri.parse(url),
                    image?.thumbnailUrl?.let(Uri::parse),
                    image?.tags.orEmpty()
                )
            )
        }
        currentIndex = GeneratedLibraryMergePolicy.shiftIndexAfterPrepend(
            currentIndex,
            existing.size,
            added
        ).coerceAtMost(currentEntries.lastIndex.coerceAtLeast(0))
        if (!isClosing) {
            tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}"
        }
    }

    private fun setupPageTurnTouches() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val tracker = ViewerPageTurnTracker(slop) { goLeft ->
            turnPage(goLeft)
        }
        findViewById<View>(R.id.zone_left).setOnTouchListener(tracker.listenerForLeftZone())
        findViewById<View>(R.id.zone_right).setOnTouchListener(tracker.listenerForRightZone())
        findViewById<View>(R.id.blank_space_handler).setOnTouchListener(tracker.listenerForFullWidth())
    }

    private fun turnPage(goLeft: Boolean) {
        if (currentEntries.isEmpty()) return
        currentIndex = if (goLeft) {
            if (currentIndex > 0) currentIndex - 1 else currentEntries.lastIndex
        } else {
            if (currentIndex < currentEntries.lastIndex) currentIndex + 1 else 0
        }
        showImage()
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
            deleteHoldHost.visibility = View.GONE
            btnCreatePreset.visibility = View.GONE
            btnReplayGeneration.visibility = View.GONE
            return
        }
        btnStartTempChat.visibility = View.VISIBLE
        deleteHoldHost.visibility = View.VISIBLE
        btnCreatePreset.visibility = View.VISIBLE
        btnReplayGeneration.visibility = View.VISIBLE
        btnStartTempChat.alpha = 1f
        deleteHoldHost.alpha = 1f
        btnCreatePreset.alpha = 1f
        btnReplayGeneration.alpha = 1f
        btnCreatePreset.setOnClickListener {
            if (!chromeVisible) {
                revealViewerChrome()
                return@setOnClickListener
            }
            val entry = currentEntries.getOrNull(currentIndex) ?: return@setOnClickListener
            GeneratedImagePresetFactory.saveFromImage(this, entry.uri, entry.uri)
        }
        btnStartTempChat.setOnClickListener {
            if (!chromeVisible) {
                revealViewerChrome()
                return@setOnClickListener
            }
            val entry = currentEntries.getOrNull(currentIndex) ?: return@setOnClickListener
            startActivity(Intent(this, ChatOverlayActivity::class.java).apply {
                putExtra("IMAGE_URI", entry.uri.toString())
                entry.thumbnailUri?.toString()?.let { putExtra("THUMBNAIL_URI", it) }
                putExtra("GENERATED_TEMP_CHAT", true)
            })
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        btnDeleteImage.setOnTouchListener { _, event ->
            if (!chromeVisible) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) revealViewerChrome()
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    deleteHoldDownX = event.rawX
                    deleteHoldDownY = event.rawY
                    btnDeleteImage.parent?.requestDisallowInterceptTouchEvent(true)
                    beginDeleteHold()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (deleteHoldActive &&
                        ViewerDeleteHoldPolicy.movedBeyondSlop(
                            deleteHoldDownX,
                            deleteHoldDownY,
                            event.rawX,
                            event.rawY,
                            slop
                        )
                    ) {
                        cancelDeleteHold()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelDeleteHold()
                    true
                }
                else -> false
            }
        }
        btnReplayGeneration.setOnClickListener {
            if (!chromeVisible) {
                revealViewerChrome()
                return@setOnClickListener
            }
            showReplayDialog()
        }
        revealViewerChrome()
    }

    private fun showReplayDialog() {
        val entry = currentEntries.getOrNull(currentIndex) ?: return
        val draft = GeneratedImageDraftStore.get(this, GeneratedImageDraftStore.keyFor(entry.uri))
        val missing = GeneratedImageReplayPolicy.missingReason(draft)
        if (missing != null) {
            Toast.makeText(this, missing, Toast.LENGTH_LONG).show()
            return
        }
        val recipe = GeneratedImageReplayPolicy.recipeFrom(draft) ?: return
        val md3 = Md3PopupDialog.wrap(this)
        val view = android.view.LayoutInflater.from(md3).inflate(R.layout.dialog_replay_generation, null)
        val summary = view.findViewById<android.widget.TextView>(R.id.tv_replay_summary)
        val promptView = view.findViewById<android.widget.TextView>(R.id.tv_replay_prompt)
        val stepsField = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_replay_steps)
        val presetRow = view.findViewById<android.widget.LinearLayout>(R.id.ll_replay_step_presets)
        summary.text = GeneratedImageReplayPolicy.summary(recipe)
        promptView.text = recipe.prompt
        stepsField.setText(recipe.steps.toString())
        val prefs = getSharedPreferences(ReplayStepPresetPolicy.PREFS_NAME, Context.MODE_PRIVATE)
        var presets = ReplayStepPresetPolicy.parse(prefs.getString(ReplayStepPresetPolicy.KEY, null))
        fun persist() {
            prefs.edit().putString(ReplayStepPresetPolicy.KEY, ReplayStepPresetPolicy.encode(presets)).apply()
        }
        fun paintPresets() {
            presetRow.removeAllViews()
            val selected = GeneratedImageReplayPolicy.clampSteps(stepsField.text?.toString()?.toIntOrNull())
            presets.forEach { value ->
                val chip = com.google.android.material.button.MaterialButton(
                    md3,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = value.toString()
                    isCheckable = true
                    isChecked = value == selected
                    minimumHeight = 0
                    minHeight = (36 * resources.displayMetrics.density).toInt()
                    textSize = 13f
                    setPadding(
                        (14 * resources.displayMetrics.density).toInt(),
                        0,
                        (14 * resources.displayMetrics.density).toInt(),
                        0
                    )
                    setOnClickListener {
                        stepsField.setText(value.toString())
                        paintPresets()
                    }
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
                presetRow.addView(chip, lp)
            }
        }
        paintPresets()
        view.findViewById<View>(R.id.btn_replay_add_step_preset).setOnClickListener {
            val steps = GeneratedImageReplayPolicy.clampSteps(stepsField.text?.toString()?.toIntOrNull())
            presets = ReplayStepPresetPolicy.add(presets, steps)
            persist()
            paintPresets()
        }
        view.findViewById<View>(R.id.btn_replay_remove_step_preset).setOnClickListener {
            val steps = GeneratedImageReplayPolicy.clampSteps(stepsField.text?.toString()?.toIntOrNull())
            presets = ReplayStepPresetPolicy.remove(presets, steps)
            persist()
            paintPresets()
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(
            md3,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog
        ).setView(view).create()
        view.findViewById<View>(R.id.btn_replay_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_replay_generate).setOnClickListener {
            val steps = GeneratedImageReplayPolicy.clampSteps(stepsField.text?.toString()?.toIntOrNull())
            dialog.dismiss()
            startReplayGeneration(recipe, steps)
        }
        dialog.show()
    }

    private fun startReplayGeneration(recipe: GeneratedImageReplayPolicy.Recipe, steps: Int) {
        ImageGenerationCoordinator.start(this, listOf(GeneratedImageReplayPolicy.request(recipe, steps)))
    }

    private fun beginDeleteHold() {
        cancelDeleteHold()
        if (currentEntries.getOrNull(currentIndex) == null) return
        deleteHoldActive = true
        lastChromeInteractionMs = System.currentTimeMillis()
        chromeHandler.removeCallbacks(hideChromeRunnable)
        showDeleteHoldProgress(0f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ViewerDeleteHoldPolicy.HOLD_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                showDeleteHoldProgress(animation.animatedValue as Float)
            }
        }
        deleteHoldAnimator = animator
        animator.start()
        chromeHandler.postDelayed(confirmDeleteHoldRunnable, ViewerDeleteHoldPolicy.HOLD_MS)
    }

    private fun cancelDeleteHold() {
        if (!deleteHoldActive && deleteHoldAnimator == null) {
            hideDeleteHoldProgress()
            return
        }
        deleteHoldActive = false
        chromeHandler.removeCallbacks(confirmDeleteHoldRunnable)
        deleteHoldAnimator?.cancel()
        deleteHoldAnimator = null
        hideDeleteHoldProgress()
        if (isGeneratedViewer && !isClosing && !isFinishing) revealViewerChrome()
    }

    private fun completeDeleteHold() {
        if (!deleteHoldActive) return
        deleteHoldActive = false
        deleteHoldAnimator?.end()
        deleteHoldAnimator = null
        showDeleteHoldProgress(1f)
        val entry = currentEntries.getOrNull(currentIndex)
        hideDeleteHoldProgress()
        if (entry != null) deleteCurrentImage(entry)
        if (isGeneratedViewer && !isClosing) revealViewerChrome()
    }

    private fun showDeleteHoldProgress(progress: Float) {
        deleteHoldRing.visibility = View.VISIBLE
        deleteHoldRing.setProgress(progress)
        deleteHoldCenterRing.visibility = View.VISIBLE
        deleteHoldCenterRing.setProgress(progress)
    }

    private fun hideDeleteHoldProgress() {
        deleteHoldRing.setProgress(0f)
        deleteHoldRing.visibility = View.INVISIBLE
        deleteHoldCenterRing.setProgress(0f)
        deleteHoldCenterRing.visibility = View.INVISIBLE
    }

    private fun deleteCurrentImage(entry: ImageEntry) {
        lifecycleScope.launch {
            val deleted = try {
                if (isRemoteGenerated || ImageStoragePolicy.isRemote(entry.uri)) {
                    GenerationAgentClient.deleteLibraryImage(this@FullScreenImageActivity, entry.uri)
                } else {
                    DataManager.deleteImageEntryFiles(this@FullScreenImageActivity, entry)
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
            deleteHoldHost.visibility = View.VISIBLE
            deleteHoldHost.alpha = 1f
            btnCreatePreset.visibility = View.VISIBLE
            btnCreatePreset.alpha = 1f
            btnReplayGeneration.visibility = View.VISIBLE
            btnReplayGeneration.alpha = 1f
        }
        if (isGeneratedViewer) {
            chromeHandler.postDelayed(hideChromeRunnable, ViewerChromePolicy.HIDE_AFTER_MS)
        }
    }

    private fun hideViewerChrome() {
        if (!isGeneratedViewer || isClosing || deleteHoldActive) return
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
            views.add(deleteHoldHost)
            views.add(btnStartTempChat)
            views.add(btnReplayGeneration)
        }
        return views
    }

    override fun onBackPressed() {
        closeViewerImmediately()
    }

    private fun closeViewerImmediately() {
        if (isClosing) return
        isClosing = true
        cancelDeleteHold()
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
        cancelDeleteHold()
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
        ImageMemoryGovernor.onViewerWorkingSet(this, workingSetKeys(viewedIndex))
    }

    private fun workingSetKeys(center: Int): Set<String> {
        val neighbors = (1..2).flatMap { distance ->
            surroundingUris(center, distance)
        }.map { it.toString() }
        val current = currentEntries.getOrNull(center)?.uri?.toString()
        return ImageMemoryPressurePolicy.keepKeys(current, neighbors)
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
