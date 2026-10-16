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
import android.widget.ImageView
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
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class FullScreenImageActivity : AppCompatActivity() {

    /** アプリが所有していない画像もOSの承認ダイアログで削除するための仕掛け */
    private val imageDeletionRequester = ImageDeletionRequester(this)

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var imageView: ProgressiveTileImageView
    private lateinit var tvCounter: TextView
    private var albumName: String = ""
    private var currentIndex: Int = 0
    private val currentEntries = mutableListOf<ImageEntry>()
    private var requestSerial = 0L
    private var activeTarget: CustomTarget<Drawable>? = null
    private var progressiveJob: Job? = null
    private var thumbnailTarget: CustomTarget<Drawable>? = null
    private lateinit var originalLoading: View
    private var completedSerial = -1L
    private var viewerPageJob: Job? = null
    private var viewerCursor: String? = null
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
    private lateinit var btnSaveImage: ImageView
    private lateinit var savedBorder: View
    private lateinit var savedBadge: View
    private var savedCheck: Job? = null
    private var saveState = ViewerSavePolicy.State.HIDDEN
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
        originalLoading = findViewById(R.id.original_loading)
        tvCounter = findViewById(R.id.tv_image_counter)
        viewerChromeBar = findViewById(R.id.viewer_chrome_bar)
        btnStartTempChat = findViewById(R.id.btn_start_temp_chat)
        deleteHoldHost = findViewById(R.id.delete_hold_host)
        btnDeleteImage = findViewById(R.id.btn_delete_image)
        deleteHoldRing = findViewById(R.id.delete_hold_ring)
        deleteHoldCenterRing = findViewById(R.id.delete_hold_center_ring)
        deleteHoldCenterRing.setStrokeWidthPx(5f * resources.displayMetrics.density)
        btnCreatePreset = findViewById(R.id.btn_create_preset_from_image)
        btnSaveImage = findViewById(R.id.btn_save_device_image)
        savedBorder = findViewById(R.id.viewer_saved_border)
        savedBadge = findViewById(R.id.viewer_saved_badge)
        btnReplayGeneration = findViewById(R.id.btn_replay_generation)
        albumName = intent.getStringExtra("ALBUM_NAME") ?: ""
        currentIndex = intent.getIntExtra("START_INDEX", 0)
        isGeneratedViewer = intent.getBooleanExtra("FROM_GENERATED_VIEWER", false) ||
            intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS") != null
        isRemoteGenerated = intent.getBooleanExtra("REMOTE_GENERATED", false)
        remoteDate = intent.getStringExtra("REMOTE_DATE").orEmpty()

        viewerCursor = intent.getStringExtra("VIEWER_NEXT_CURSOR")
        loadImages()
        showImage()
        setupViewerActions()
        btnSaveImage.setOnClickListener {
            if (saveState != ViewerSavePolicy.State.READY) {
                return@setOnClickListener
            }
            val source = currentEntries.getOrNull(currentIndex)?.uri ?: return@setOnClickListener
            GeneratedImageDeviceStore.saveInBackground(this, source)
        }
        lifecycleScope.launch {
            GeneratedImageDeviceStore.savingUri.collect { refreshSavedState() }
        }
        setupPageTurnTouches()
        observeLiveLibrary()
    }

    override fun onResume() {
        super.onResume()
        refreshSavedState()
    }

    private fun refreshSavedState() {
        savedCheck?.cancel()
        val source = currentEntries.getOrNull(currentIndex)?.uri
        if (!isGeneratedViewer || source == null || isClosing) {
            renderSaveState(ViewerSavePolicy.State.HIDDEN)
            return
        }
        renderSaveState(ViewerSavePolicy.state(source.toString(), null, GeneratedImageDeviceStore.savingUri.value))
        savedCheck = lifecycleScope.launch {
            val saved = GeneratedSavedImages.contains(this@FullScreenImageActivity, source)
            val key = GeneratedImageIdentity.canonicalKey(source.toString())
            val current = currentEntries.getOrNull(currentIndex)?.uri?.toString()
            if (isClosing || key != GeneratedImageIdentity.canonicalKey(current)) {
                return@launch
            }
            val keys = if (saved) setOf(key) else emptySet()
            renderSaveState(ViewerSavePolicy.state(current, keys, GeneratedImageDeviceStore.savingUri.value))
        }
    }

    private fun renderSaveState(state: ViewerSavePolicy.State) {
        saveState = state
        val saved = state == ViewerSavePolicy.State.SAVED
        savedBorder.visibility = if (saved) View.VISIBLE else View.GONE
        savedBadge.visibility = if (saved) View.VISIBLE else View.GONE
        btnSaveImage.visibility = if (state == ViewerSavePolicy.State.HIDDEN) View.GONE else View.VISIBLE
        btnSaveImage.isEnabled = state == ViewerSavePolicy.State.READY
        btnSaveImage.setImageResource(if (saved) R.drawable.ic_cyber_check else R.drawable.ic_viewer_download)
        val label = when (state) {
            ViewerSavePolicy.State.SAVED -> R.string.generated_saved_badge
            ViewerSavePolicy.State.SAVING -> R.string.viewer_save_in_progress
            ViewerSavePolicy.State.WAITING -> R.string.viewer_save_other
            ViewerSavePolicy.State.CHECKING -> R.string.viewer_save_checking
            else -> R.string.viewer_save_device
        }
        btnSaveImage.contentDescription = getString(label)
        btnSaveImage.tooltipText = getString(label)
        btnSaveImage.imageTintList = android.content.res.ColorStateList.valueOf(
            when (state) {
                ViewerSavePolicy.State.SAVED -> android.graphics.Color.rgb(105, 240, 174)
                ViewerSavePolicy.State.READY -> android.graphics.Color.WHITE
                else -> android.graphics.Color.GRAY
            }
        )
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
        val previous = currentEntries.associateBy { it.uri.toString() }
        currentEntries.clear()
        merged.forEach { url ->
            val image = byUrl[url]
            if (image == null) {
                previous[url]?.let { currentEntries.add(it) }
                return@forEach
            }
            currentEntries.add(
                GeneratedImageDraftStore.entryFor(
                    this,
                    Uri.parse(url),
                    Uri.parse(image.thumbnailUrl),
                    image.tags
                )
            )
        }
        currentIndex = GeneratedLibraryMergePolicy.shiftIndexAfterPrepend(
            currentIndex,
            existing.size,
            added
        ).coerceAtMost(currentEntries.lastIndex.coerceAtLeast(0))
        if (!isClosing) {
            tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}${if (viewerCursor != null) { "+" } else { "" }}"
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
        if (goLeft && currentIndex == 0 && viewerCursor != null) {
            return
        }
        if (!goLeft && currentIndex == currentEntries.lastIndex && viewerCursor != null) {
            loadViewerPage()
            Toast.makeText(this, "次の一覧を読み込み中です。", Toast.LENGTH_SHORT).show()
            return
        }
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
        val generateButton = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_replay_generate)
        if (GenerationProgressManager.state.value.isGenerating || GenerationAgentClient.hasPendingJob(this)) {
            generateButton.setText(R.string.generate_next_insert)
        }
        generateButton.setOnClickListener {
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
        // PC側の画像はPCのエージェントへ削除を依頼する
        if (isRemoteGenerated || ImageStoragePolicy.isRemote(entry.uri)) {
            lifecycleScope.launch {
                val deleted = try {
                    GenerationAgentClient.deleteLibraryImage(this@FullScreenImageActivity, entry.uri)
                } catch (_: Exception) {
                    false
                }
                if (deleted) {
                    finalizeImageDeletion(entry)
                } else {
                    Toast.makeText(this@FullScreenImageActivity, "削除に失敗しました。", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // 端末内の画像は、アプリ外の画像でもOSの承認ダイアログで削除する
        imageDeletionRequester.request(listOf(entry)) { outcome ->
            if (outcome.deleted.isNotEmpty()) {
                finalizeImageDeletion(entry)
            } else {
                Toast.makeText(
                    this@FullScreenImageActivity,
                    ImageDeleteReportPolicy.singleFailure(outcome.userDeclined),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun finalizeImageDeletion(entry: ImageEntry) {
        GeneratedImageDraftStore.deleteImageAndMaybeChat(this, entry.uri)
        DataManager.allImages.removeAll { it.uri.toString() == entry.uri.toString() }
        DataManager.saveData(this)
        deletedUris.add(entry.uri.toString())
        val removedAt = currentEntries.indexOfFirst { it.uri.toString() == entry.uri.toString() }
        if (removedAt < 0) return
        currentEntries.removeAt(removedAt)
        if (currentEntries.isEmpty()) {
            Toast.makeText(this, "削除しました。", Toast.LENGTH_SHORT).show()
            closeViewerImmediately()
            return
        }
        currentIndex = removedAt.coerceAtMost(currentEntries.lastIndex)
        Toast.makeText(this, "削除しました。", Toast.LENGTH_SHORT).show()
        showImage()
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
        viewerPageJob?.cancel()
        cancelDeleteHold()
        requestSerial++
        progressiveJob?.cancel()
        progressiveJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        activeTarget?.let { Glide.with(this).clear(it) }
        activeTarget = null
        imageView.prepareForLoad()
        val albumKey = intent.getStringExtra("VIEWER_ALBUM_KEY")
        if (albumKey != null) {
            ViewerImageHandoff.replace(albumKey, currentEntries)
        }
        setResult(RESULT_OK, Intent().apply {
            putExtra("VIEWER_ALBUM_KEY", albumKey)
            putExtra("VIEWER_NEXT_CURSOR", viewerCursor)
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
        savedCheck?.cancel()
        thumbnailTarget?.let { Glide.with(this).clear(it) }
        activeTarget?.let { Glide.with(this).clear(it) }
        super.onDestroy()
    }

    private fun loadImages() {
        val handoffKey = intent.getStringExtra("VIEWER_ALBUM_KEY")
        if (handoffKey != null) {
            val album = ViewerImageHandoff.entries(handoffKey)
            currentEntries.clear()
            if (album != null) {
                currentEntries.addAll(album)
            } else {
                intent.getStringExtra("VIEWER_CURRENT_URI")?.let {
                    currentEntries.add(ImageEntry(Uri.parse(it), thumbnailUri = intent.getStringExtra("THUMBNAIL_URI")?.let(Uri::parse)))
                }
                currentIndex = 0
            }
            return
        }
        val virtualUris = intent.getStringArrayListExtra("VIRTUAL_ALBUM_URIS")
        if (virtualUris != null) {
            currentEntries.clear()
            val thumbnails = intent.getStringArrayListExtra("VIRTUAL_ALBUM_THUMBNAIL_URIS")
            virtualUris.forEachIndexed { index, uri ->
                currentEntries.add(ImageEntry(Uri.parse(uri), thumbnailUri = thumbnails?.getOrNull(index)?.let(Uri::parse)))
            }
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
        if (currentIndex >= currentEntries.size - 12) {
            loadViewerPage()
        }
        refreshSavedState()
        val viewedIndex = currentIndex
        val serial = ++requestSerial
        progressiveJob?.cancel()
        progressiveJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        activeTarget?.let { Glide.with(this).clear(it) }
        activeTarget = null
        tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}${if (viewerCursor != null) { "+" } else { "" }}"
        imageView.prepareForLoad()

        originalLoading.visibility = View.VISIBLE
        thumbnailTarget?.let { Glide.with(this).clear(it) }
        thumbnailTarget = null
        val preview = ViewerImageHandoff.preview(entry.uri.toString(), resources)
        if (preview != null) {
            applyImageRatio(preview.intrinsicWidth, preview.intrinsicHeight)
            imageView.showCompleteDrawable(preview)
        } else {
            showThumbnail(entry, serial)
        }
        if (ImageStoragePolicy.isRemote(entry.uri)) {
            progressiveJob = lifecycleScope.launch {
                try {
                    val file = OriginalImageMemoryCache.getFile(entry.uri)
                    if (serial == requestSerial) {
                        displayOriginal(file, entry.uri, serial)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (serial == requestSerial) {
                        originalLoading.visibility = View.GONE
                        Toast.makeText(this@FullScreenImageActivity, "原寸画像の取得に失敗しました。開き直して再試行してください。", Toast.LENGTH_LONG).show()
                    }
                }
            }
            startStagedPrefetch(viewedIndex, entry.uri, serial)
        } else {
            displayOriginal(entry.uri, entry.uri, serial)
        }
        ImageMemoryGovernor.onViewerWorkingSet(this, workingSetKeys(viewedIndex))
    }

    private fun loadViewerPage() {
        val cursor = viewerCursor ?: return
        if (viewerPageJob?.isActive == true || !isRemoteGenerated) {
            return
        }
        viewerPageJob = lifecycleScope.launch {
            try {
                val page = GeneratedFolderRepository.page(this@FullScreenImageActivity, remoteDate, cursor)
                if (!page.indexing) {
                    val keys = currentEntries.map { GeneratedImageIdentity.canonicalKey(it.uri.toString()) }.toHashSet()
                    currentEntries.addAll(page.entries.filter { keys.add(GeneratedImageIdentity.canonicalKey(it.uri.toString())) })
                    viewerCursor = page.next
                    tvCounter.text = "${currentIndex + 1} / ${currentEntries.size}${if (viewerCursor != null) { "+" } else { "" }}"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Toast.makeText(this@FullScreenImageActivity, "次の一覧を取得できません。次の画像へ進む操作で再試行します。", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showThumbnail(entry: ImageEntry, serial: Long) {
        val uri = entry.thumbnailUri ?: return
        val model = ThumbnailLocalCache.existingLibrary(this, uri.toString()) ?: uri
        val target = object : CustomTarget<Drawable>(ImageMemoryPressurePolicy.GRID_THUMB_WIDTH, ImageMemoryPressurePolicy.GRID_THUMB_HEIGHT) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (serial == requestSerial && completedSerial != serial) {
                    applyImageRatio(resource.intrinsicWidth, resource.intrinsicHeight)
                    imageView.showCompleteDrawable(resource)
                }
            }
            override fun onLoadCleared(placeholder: Drawable?) { }
        }
        thumbnailTarget = target
        Glide.with(this).load(model).diskCacheStrategy(DiskCacheStrategy.ALL).dontAnimate().into(target)
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
            if (OriginalImageMemoryCache.getFileIfPresent(currentUri) == null) {
                try {
                    OriginalImageMemoryCache.getFile(currentUri)
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
        val indices = if (viewerCursor != null) {
            listOf(center - distance, center + distance).filter { it in currentEntries.indices }
        } else {
            CircularPrefetchPlanner.ring(center, currentEntries.size, distance)
        }
        return indices.map { currentEntries[it].uri }
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
        val target = object : CustomTarget<Drawable>(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                if (serial != requestSerial) return
                completedSerial = serial
                originalLoading.visibility = View.GONE
                val width = resource.intrinsicWidth
                val height = resource.intrinsicHeight
                if (width > 0 && height > 0) applyImageRatio(width, height)
                imageView.showCompleteDrawable(resource)
            }

            override fun onLoadCleared(placeholder: Drawable?) { }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (serial == requestSerial) {
                    originalLoading.visibility = View.GONE
                    Toast.makeText(this@FullScreenImageActivity, "画像を表示できません。開き直して再試行してください。", Toast.LENGTH_LONG).show()
                }
            }
        }
        activeTarget = target
        Glide.with(this)
            .load(model)
            .override(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .fitCenter()
            .dontAnimate()
            .diskCacheStrategy(
                if (model is ByteArray) DiskCacheStrategy.NONE
                else ImageStoragePolicy.glideDiskCache(sourceUri, DiskCacheStrategy.RESOURCE)
            )
            .into(target)
    }
}
