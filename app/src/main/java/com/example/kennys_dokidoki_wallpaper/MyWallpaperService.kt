package com.example.kennys_dokidoki_wallpaper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val TOOL_FINDER_ACTION = "TOOL_FINDER"
private const val DOUBLE_TAP_HOLD_KEY = "action_tap_2_hold"
private const val TRIPLE_TAP_HOLD_KEY = "action_tap_3_hold"

private object ToolFinderSpec {
    const val EXPANSION_DELAY_MS = 600L
    const val OPEN_ANIMATION_MS = 280L
    const val CLOSE_ANIMATION_MS = 190L
    const val BASE_RADIUS_DP = 112f
    const val CENTER_HOLE_RADIUS_DP = 21f
    const val MENU_RADIUS_DP = 148f
    const val HORIZONTAL_EXTENT_DP = 160f
    const val MAX_EXTENT_DP = 190f
    const val OPTION_WIDTH_DP = 58f
    const val OPTION_HEIGHT_DP = 58f
    const val OPTION_ANGLE_START = -150f
    const val OPTION_ANGLE_STEP = 24f
    const val MAX_OPTION_ANGLE_DELTA = 13f
    const val OPTION_PICK_RADIUS_DP = 136f
    const val FULL_CIRCLE_DEGREES = 360f
    const val REVEAL_START_ANGLE_DEGREES = -90f
}

class MyWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MyEngine()
    }

    inner class MyEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val engineJob = Job()
        private val engineScope = CoroutineScope(Dispatchers.Main + engineJob)
        private var isLoading = false

        // 見えていない間は描かない・抱えない。再表示された時に読み直す。
        private var engineVisible = true
        private var needsReload = false

        private var currentBitmap: Bitmap? = null
        private var previousBitmap: Bitmap? = null
        private var currentEntry: ImageEntry? = null
        private var previousEntry: ImageEntry? = null
        
        private var imageEntries = mutableListOf<ImageEntry>()
        private var currentImageIndex = 0

        private var tapCount: Int = 0
        private val tapTimeout: Long = 350L
        private val tapHandler = Handler(Looper.getMainLooper())
        private val tapRunnable = Runnable {
            executeTapAction(tapCount)
            tapCount = 0
        }

        private var isAnimating = false
        private var animationProgress: Float = 0f
        private var animator: ValueAnimator? = null
        private val prevPaint = Paint().apply {
            isFilterBitmap = true
            isDither = true
        }
        private val currPaint = Paint().apply {
            isFilterBitmap = true
            isDither = true
        }

        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private val TOUCH_TOLERANCE = 50f
        private var lastTapTime = 0L
        
        private var holdRunnable: Runnable? = null
        private val holdTimeout = 400L
        private var toolFinder: ToolFinderOverlay? = null
        private var toolFinderAnimator: ValueAnimator? = null
        private var toolFinderExpansionRunnable: Runnable? = null

        private var longPressRunnable: Runnable? = null
        private val longPress1sTimeout = 1000L

        private var showTextProgress = 0f
        private var textAnimator: ValueAnimator? = null
        private val textHandler = Handler(Looper.getMainLooper())
        private val textHideRunnable = Runnable { startTextFadeOut() }
        
        private val textPaint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#8892B0")
                val density = applicationContext.resources.displayMetrics.density
                textSize = 10f * density
                textAlign = Paint.Align.CENTER
            }
        }

        private val tapReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.example.kennys_dokidoki_wallpaper.ACTION_SIMULATE_TAP" -> {
                        val count = intent.getIntExtra("tap_count", 0)
                        if (count > 0) executeTapAction(count)
                    }
                    "com.example.kennys_dokidoki_wallpaper.ACTION_EXECUTE_ACTION" -> {
                        val actionStr = intent.getStringExtra("action_string")
                        if (actionStr != null) {
                            executeActionString(actionStr)
                        }
                    }
                    "com.example.kennys_dokidoki_wallpaper.ACTION_SHOW_SET_NAME" -> {
                        showSetNameText()
                    }
                    "com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED" -> {
                        Handler(Looper.getMainLooper()).post {
                            if (!engineVisible) {
                                needsReload = true
                                return@post
                            }
                            if (loadActiveAlbumAndIndex() && imageEntries.isNotEmpty()) {
                                currentImageIndex = currentImageIndex.coerceIn(0, imageEntries.size - 1)
                                val entry = imageEntries[currentImageIndex]
                                // URIが違うか、今のビットマップが空っぽなら読み込み開始よ！
                                if (entry.uri != currentEntry?.uri || currentBitmap == null) {
                                    loadAndSetCurrentBitmap(entry) { draw() }
                                } else {
                                    // URIが同じでも、クロップ範囲などが変わっている可能性があるから
                                    // エントリーを最新のものに差し替えて再描画するわよ！
                                    currentEntry = entry
                                    draw()
                                }
                            }
                            showSetNameText()
                        }
                    }
                    else -> {}
                }
            }
        }

        init {
            setTouchEventsEnabled(true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            settingsPrefs.registerOnSharedPreferenceChangeListener(this)
            
            val wallpaperPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            wallpaperPrefs.registerOnSharedPreferenceChangeListener(this)

            val filter = IntentFilter().apply {
                addAction("com.example.kennys_dokidoki_wallpaper.ACTION_SIMULATE_TAP")
                addAction("com.example.kennys_dokidoki_wallpaper.ACTION_EXECUTE_ACTION")
                addAction("com.example.kennys_dokidoki_wallpaper.ACTION_SHOW_SET_NAME")
                addAction("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED")
            }
            ContextCompat.registerReceiver(applicationContext, tapReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        override fun onDestroy() {
            super.onDestroy()
            goQuiet()
            engineJob.cancel()
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(this)
            
            val wallpaperPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            wallpaperPrefs.unregisterOnSharedPreferenceChangeListener(this)
            
            applicationContext.unregisterReceiver(tapReceiver)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "active_album_name" || key == "active_album_name_homescreen" || key == "active_album_name_chat" || key == "is_chat_active" || key == "active_image_index" || key == "all_images" || key == "image_sets" || key == DataManager.KEY_REVISION) {
                Handler(Looper.getMainLooper()).post {
                    // 見えていない時の設定変更は次に表示される時まで寝かせる。
                    if (!engineVisible) {
                        needsReload = true
                        return@post
                    }
                    if (loadActiveAlbumAndIndex() && imageEntries.isNotEmpty()) {
                        currentImageIndex = currentImageIndex.coerceIn(0, imageEntries.size - 1)
                        val entry = imageEntries[currentImageIndex]
                        // 設定が変わっても、画像が違うか空っぽの時だけロードするわ
                        if (entry.uri != currentEntry?.uri || currentBitmap == null) {
                            if (key == "is_chat_active" && currentBitmap != null) {
                                prepareCrossfade(entry) { nextBitmap ->
                                    previousBitmap = currentBitmap; previousEntry = currentEntry
                                    currentBitmap = nextBitmap; currentEntry = entry
                                    startAnimator()
                                }
                            } else {
                                loadAndSetCurrentBitmap(entry) { draw() }
                            }
                        } else {
                            // URIが同じでも、クロップ範囲などが変わっている可能性があるから
                            // エントリーを最新のものに差し替えて再描画するわよ！
                            currentEntry = entry
                            draw()
                        }
                    }
                    showSetNameText()
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            event?.let {
                when (it.action) {
                    MotionEvent.ACTION_DOWN -> {
                        showSetNameText()
                        
                        // 指が触れた瞬間にタップ判定タイマーを停止する（これによって長押し中に前のタップのアクションが暴発するのを防ぐ）
                        tapHandler.removeCallbacks(tapRunnable)
                        
                        downX = it.x
                        downY = it.y
                        downTime = System.currentTimeMillis()

                        if (tapCount == 1 && (downTime - lastTapTime) <= tapTimeout) {
                            holdRunnable = Runnable {
                                holdRunnable = null
                                executeHoldAction(DOUBLE_TAP_HOLD_KEY)
                            }
                            tapHandler.postDelayed(holdRunnable!!, holdTimeout)
                        } else if (tapCount == 2 && (downTime - lastTapTime) <= tapTimeout) {
                            holdRunnable = Runnable {
                                holdRunnable = null
                                executeHoldAction(TRIPLE_TAP_HOLD_KEY)
                            }
                            tapHandler.postDelayed(holdRunnable!!, holdTimeout)
                        } else {
                            if ((downTime - lastTapTime) > tapTimeout) {
                                tapCount = 0
                            }
                            if (tapCount == 0) {
                                longPressRunnable = Runnable {
                                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                    val action = prefs.getString("action_hold_2s", "NONE") ?: "NONE"
                                    executeActionString(action)
                                    tapCount = 0
                                }
                                tapHandler.postDelayed(longPressRunnable!!, longPress1sTimeout)
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (toolFinder != null) {
                            updateToolFinder(it.x, it.y)
                        } else {
                            val dx = it.x - downX
                            val dy = it.y - downY
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (distance > TOUCH_TOLERANCE) {
                                holdRunnable?.let { r -> tapHandler.removeCallbacks(r); holdRunnable = null }
                                longPressRunnable?.let { r -> tapHandler.removeCallbacks(r); longPressRunnable = null }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holdRunnable?.let { r -> tapHandler.removeCallbacks(r); holdRunnable = null }
                        longPressRunnable?.let { r -> tapHandler.removeCallbacks(r); longPressRunnable = null }
                        if (toolFinder != null) {
                            // Release the last visible highlight, not a possibly jittered up-event coordinate.
                            closeToolFinder(it.action == MotionEvent.ACTION_UP)
                            return
                        }

                        if (it.action == MotionEvent.ACTION_UP) {
                            val upX = it.x
                            val upY = it.y
                            val upTime = System.currentTimeMillis()
                            val dx = upX - downX
                            val dy = upY - downY
                            val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            
                            if (distance <= TOUCH_TOLERANCE && (upTime - downTime) < 300L) {
                                lastTapTime = upTime
                                handleTap()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        private fun executeHoldAction(prefKey: String) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val action = prefs.getString(prefKey, "NONE") ?: "NONE"
            tapCount = 0
            if (action == TOOL_FINDER_ACTION) {
                showToolFinder()
                return
            }
            executeActionString(action)
        }

        private fun showToolFinder() {
            clearToolFinder()
            val frame = surfaceHolder.surfaceFrame
            val width = resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: frame.width()
            val finder = ToolFinderOverlay(applicationContext, downX, downY, width)
            toolFinder = finder
            toolFinderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ToolFinderSpec.OPEN_ANIMATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (toolFinder === finder) {
                        finder.revealProgress = it.animatedValue as Float
                        draw()
                    }
                }
                start()
            }
        }

        private fun updateToolFinder(x: Float, y: Float) {
            val finder = toolFinder ?: return
            if (finder.isClosing) {
                return
            }
            val wasAppSelected = finder.isAppSelected()
            finder.update(x, y)
            val isAppSelected = finder.isAppSelected()
            if (!finder.isExpanded && isAppSelected && !wasAppSelected) {
                scheduleToolFinderExpansion(finder)
            } else if (!isAppSelected) {
                cancelToolFinderExpansion()
            }
            draw()
        }

        private fun scheduleToolFinderExpansion(finder: ToolFinderOverlay) {
            cancelToolFinderExpansion()
            val runnable = Runnable {
                toolFinderExpansionRunnable = null
                if (toolFinder !== finder || finder.isClosing || !finder.expand()) {
                    return@Runnable
                }
                draw()
            }
            toolFinderExpansionRunnable = runnable
            tapHandler.postDelayed(runnable, ToolFinderSpec.EXPANSION_DELAY_MS)
        }

        private fun cancelToolFinderExpansion() {
            toolFinderExpansionRunnable?.let(tapHandler::removeCallbacks)
            toolFinderExpansionRunnable = null
        }

        private fun closeToolFinder(commitSelection: Boolean) {
            val finder = toolFinder ?: return
            if (finder.isClosing) {
                return
            }
            cancelToolFinderExpansion()
            finder.beginClosing()
            val previousAnimator = toolFinderAnimator
            toolFinderAnimator = null
            previousAnimator?.cancel()
            val exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ToolFinderSpec.CLOSE_ANIMATION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (toolFinder === finder) {
                        finder.exitProgress = it.animatedValue as Float
                        draw()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (toolFinder !== finder || toolFinderAnimator !== animation) {
                            return
                        }
                        toolFinderAnimator = null
                        toolFinder = null
                        draw()
                        launchToolFinderSelection(finder, commitSelection)
                    }
                })
            }
            toolFinderAnimator = exitAnimator
            exitAnimator.start()
        }

        private fun launchToolFinderSelection(finder: ToolFinderOverlay, commitSelection: Boolean) {
            if (!commitSelection) {
                return
            }
            when {
                finder.isAppSelected() -> openApp()
                finder.isChatSelected() -> toggleAiChatOverlay()
                else -> finder.selectedSection()?.let(::openToolFinderSection)
            }
        }

        private fun openToolFinderSection(section: HomeSection) {
            val intent = if (section == HomeSection.DIARY) {
                Intent(applicationContext, DiaryCalendarActivity::class.java)
            } else {
                Intent(applicationContext, MainActivity::class.java)
                    .putExtra(HomeSection.EXTRA_KEY, section.name)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            applicationContext.startActivity(intent)
        }

        private fun clearToolFinder() {
            cancelToolFinderExpansion()
            val animation = toolFinderAnimator
            toolFinderAnimator = null
            animation?.cancel()
            toolFinder = null
        }

        private fun showSetNameText() {
            if (!engineVisible) return
            textHandler.removeCallbacks(textHideRunnable)
            textAnimator?.cancel()
            if (showTextProgress < 1f) {
                textAnimator = ValueAnimator.ofFloat(showTextProgress, 1f).apply {
                    duration = 150L
                    addUpdateListener { showTextProgress = it.animatedValue as Float; draw() }
                    start()
                }
            } else {
                showTextProgress = 1f
                draw()
            }
            textHandler.postDelayed(textHideRunnable, 3000L)
        }

        private fun startTextFadeOut() {
            textAnimator?.cancel()
            textAnimator = ValueAnimator.ofFloat(showTextProgress, 0f).apply {
                duration = 300L
                addUpdateListener { showTextProgress = it.animatedValue as Float; draw() }
                start()
            }
        }

        private fun handleTap() {
            tapCount++
            tapHandler.removeCallbacks(tapRunnable)
            tapHandler.postDelayed(tapRunnable, tapTimeout)
        }

        private fun executeTapAction(count: Int) {
            if (count < 2) return
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val defaultAction = if (count == 2) "NEXT_IMAGE" else if (count == 3) "NEXT_SET" else "NONE"
            val action = prefs.getString("action_tap_$count", defaultAction) ?: "NONE"
            executeActionString(action)
        }

        private fun executeActionString(action: String) {
            when {
                action == "NEXT_IMAGE" -> startCrossfadeToNextImage()
                action == "NEXT_SET" -> startCrossfadeToNextAlbum()
                action == "TOGGLE_AI_CHAT" -> toggleAiChatOverlay()
                action == "OPEN_APP" -> openApp()
                action == "CROP_IMAGE" -> startCropImage()
                action == "EDIT_TAGS" -> startEditTags()
                action == "EDIT_ACTIVE_SET" -> startEditActiveSet()
                action.startsWith("SPECIFIC_SET:") -> {
                    val setName = action.substringAfter("SPECIFIC_SET:")
                    startCrossfadeToSpecificAlbum(setName)
                }
                else -> {}
            }
        }

        private fun toggleAiChatOverlay() {
            try {
                val intent = Intent(applicationContext, ChatOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                applicationContext.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.e("MyWallpaperService", "toggleAiChatOverlay failed", e)
            }
        }

        private fun openApp() {
            val intent = Intent(applicationContext, HomeCarouselActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            applicationContext.startActivity(intent)
        }

        private fun startCropImage() {
            if (imageEntries.isEmpty() || currentImageIndex !in imageEntries.indices) return
            val entry = imageEntries[currentImageIndex]
            val intent = Intent(applicationContext, CropHandlerActivity::class.java).apply {
                putExtra("SOURCE_URI", entry.uri.toString())
                putExtra("IMAGE_URI", entry.uri.toString()) 
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
        }

        private fun startEditTags() {
            if (imageEntries.isEmpty() || currentImageIndex !in imageEntries.indices) return
            val entry = imageEntries[currentImageIndex]
            val intent = Intent(applicationContext, ImageTagEditorActivity::class.java).apply {
                putExtra("IMAGE_URI", entry.uri.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
        }

        private fun getActiveSetName(settingsPrefs: SharedPreferences): String? {
            val isChatActive = settingsPrefs.getBoolean("is_chat_active", false)
            return if (isChatActive) {
                settingsPrefs.getString("active_album_name_chat", null)
                    ?: settingsPrefs.getString("active_album_name", null)
            } else {
                settingsPrefs.getString("active_album_name_homescreen", null)
                    ?: settingsPrefs.getString("active_album_name", null)
            }
        }

        private fun getFilteredActiveSets(isChatActive: Boolean): List<ImageSet> {
            return DataManager.imageSetList.filter { set ->
                if (!set.isActive) return@filter false
                if (isChatActive) {
                    set.usage == ImageSetUsage.CHAT || set.usage == ImageSetUsage.BOTH
                } else {
                    set.usage == ImageSetUsage.HOMESCREEN || set.usage == ImageSetUsage.BOTH
                }
            }
        }

        private fun startEditActiveSet() {
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val currentSetName = getActiveSetName(settingsPrefs) ?: return
            val intent = Intent(applicationContext, ImageTagEditorActivity::class.java).apply {
                putExtra("SET_NAME", currentSetName)
                putExtra("CREATE_NEW_SET", false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applicationContext.startActivity(intent)
        }

        private fun loadActiveAlbumAndIndex(): Boolean {
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val activeSetName = getActiveSetName(settingsPrefs) ?: return false
            DataManager.loadData(this@MyWallpaperService)
            val set = DataManager.imageSetList.find { it.name == activeSetName } ?: return false
            val filteredEntries = set.filterImages(DataManager.allImages)
            val activeEntries = filteredEntries.filter { it.isActive }
            if (activeEntries.isEmpty()) return false
            imageEntries = activeEntries.toMutableList()
            val savedIndex = settingsPrefs.getInt("last_index_for_album_$activeSetName", 0)
            currentImageIndex = savedIndex.coerceIn(0, imageEntries.size - 1)
            return true
        }

        private fun saveCurrentIndex() {
            // 非表示時のアニメキャンセルで来た保存はしない。非表示中の循環通知を止める。
            if (!engineVisible) return
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val activeSetName = getActiveSetName(settingsPrefs) ?: return
            val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED")
            intent.setPackage(applicationContext.packageName)
            settingsPrefs.edit()
                .putInt("active_image_index", currentImageIndex) 
                .putInt("last_index_for_album_$activeSetName", currentImageIndex) 
                .apply()
            applicationContext.sendBroadcast(intent)
        }

        private fun startAnimator() {
            if (animator?.isRunning == true) animator?.cancel()
            isAnimating = true
            animationProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200L
                interpolator = LinearInterpolator()
                addUpdateListener { anim -> animationProgress = anim.animatedValue as Float; draw() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        isAnimating = false
                        previousBitmap = null; previousEntry = null
                        animationProgress = 1f; saveCurrentIndex(); draw()
                    }
                    override fun onAnimationCancel(animation: Animator) {
                        isAnimating = false
                        previousBitmap = null; previousEntry = null
                        animationProgress = 1f; saveCurrentIndex(); draw()
                    }
                })
                start()
            }
        }

        private fun decodeBitmap(uri: Uri): Bitmap? {
            return try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val displayMetrics = android.util.DisplayMetrics()
                
                // より確実に物理サイズを取得するわよ！っ！
                @Suppress("DEPRECATION")
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    try { this@MyWallpaperService.display } catch (e: Exception) { wm.defaultDisplay }
                } else {
                    wm.defaultDisplay
                }
                
                display?.getRealMetrics(displayMetrics)
                
                var reqWidth = displayMetrics.widthPixels
                var reqHeight = displayMetrics.heightPixels
                
                // 万が一0だったら、フォールバックするわ（これ大事！）
                if (reqWidth <= 0 || reqHeight <= 0) {
                    val fallback = applicationContext.resources.displayMetrics
                    reqWidth = fallback.widthPixels
                    reqHeight = fallback.heightPixels
                }
                
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                
                if (options.outWidth <= 0 || options.outHeight <= 0) return null

                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
                options.inJustDecodeBounds = false
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            } catch (e: Exception) { 
                android.util.Log.e("MyWallpaperService", "decodeBitmap failed", e)
                null 
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2; val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
            }
            return inSampleSize
        }

        private fun loadAndSetCurrentBitmap(entry: ImageEntry, onReady: (() -> Unit)? = null) {
            isLoading = true
            engineScope.launch(Dispatchers.IO) {
                val bmp = decodeBitmap(entry.displayUri)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    // 読み込み中に隠れていたら捨てて、再表示に読み直す。
                    if (!engineVisible) {
                        needsReload = true
                        return@withContext
                    }
                    if (bmp != null) { currentBitmap = bmp; currentEntry = entry; onReady?.invoke() }
                }
            }
        }

        private fun prepareCrossfade(nextEntry: ImageEntry, onReady: (Bitmap) -> Unit) {
            isLoading = true
            engineScope.launch(Dispatchers.IO) {
                val nextBitmap = decodeBitmap(nextEntry.displayUri)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    if (!engineVisible) {
                        needsReload = true
                        return@withContext
                    }
                    if (nextBitmap != null) onReady(nextBitmap)
                }
            }
        }

        private fun startCrossfadeToNextImage() {
            if (isAnimating || isLoading) return
            if (!loadActiveAlbumAndIndex() || imageEntries.isEmpty()) return
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val isShuffle = settingsPrefs.getBoolean("shuffle_images", false)
            val nextIndex = if (isShuffle && imageEntries.size > 1) {
                var randomIndex = (0 until imageEntries.size).random()
                while (randomIndex == currentImageIndex) randomIndex = (0 until imageEntries.size).random()
                randomIndex
            } else (currentImageIndex + 1) % imageEntries.size
            val nextEntry = imageEntries[nextIndex]
            prepareCrossfade(nextEntry) { nextBitmap ->
                previousBitmap = currentBitmap; previousEntry = currentEntry
                currentBitmap = nextBitmap; currentEntry = nextEntry; currentImageIndex = nextIndex
                startAnimator()
            }
        }

        private fun startCrossfadeToNextAlbum() {
            if (isAnimating || isLoading) return
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val currentSetName = getActiveSetName(settingsPrefs) ?: return
            DataManager.loadData(this@MyWallpaperService)
            val isChatActive = settingsPrefs.getBoolean("is_chat_active", false)
            val activeSets = getFilteredActiveSets(isChatActive)
            if (activeSets.isEmpty()) return
            val currentIndex = activeSets.indexOfFirst { it.name == currentSetName }
            val nextSet = if (currentIndex == -1 || currentIndex == activeSets.size - 1) activeSets[0] else activeSets[currentIndex + 1]
            val newEntries = nextSet.filterImages(DataManager.allImages).filter { it.isActive }
            if (newEntries.isEmpty()) return
            val lastIndexForNextSet = settingsPrefs.getInt("last_index_for_album_${nextSet.name}", 0)
            val nextImageIndex = lastIndexForNextSet.coerceIn(0, newEntries.size - 1)
            val nextEntry = newEntries[nextImageIndex]
            prepareCrossfade(nextEntry) { nextBitmap ->
                val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED")
                intent.setPackage(applicationContext.packageName)
                val isChatActive = settingsPrefs.getBoolean("is_chat_active", false)
                val keyToSave = if (isChatActive) "active_album_name_chat" else "active_album_name_homescreen"
                settingsPrefs.edit().putString(keyToSave, nextSet.name).putInt("active_image_index", nextImageIndex).putInt("last_index_for_album_${nextSet.name}", nextImageIndex).apply()
                applicationContext.sendBroadcast(intent)
                previousBitmap = currentBitmap; previousEntry = currentEntry
                imageEntries = newEntries.toMutableList(); currentImageIndex = nextImageIndex; currentEntry = nextEntry; currentBitmap = nextBitmap
                startAnimator()
            }
        }

        private fun startCrossfadeToSpecificAlbum(setName: String) {
            if (isAnimating || isLoading) return
            DataManager.loadData(this@MyWallpaperService)
            val set = DataManager.imageSetList.find { it.name == setName } ?: return
            val newEntries = set.filterImages(DataManager.allImages).filter { it.isActive }
            if (newEntries.isEmpty()) return
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lastIndex = settingsPrefs.getInt("last_index_for_album_$setName", 0)
            val nextImageIndex = lastIndex.coerceIn(0, newEntries.size - 1)
            val nextEntry = newEntries[nextImageIndex]
            prepareCrossfade(nextEntry) { nextBitmap ->
                val intent = Intent("com.example.kennys_dokidoki_wallpaper.ACTION_WALLPAPER_CHANGED")
                intent.setPackage(applicationContext.packageName)
                val isChatActive = settingsPrefs.getBoolean("is_chat_active", false)
                val keyToSave = if (isChatActive) "active_album_name_chat" else "active_album_name_homescreen"
                settingsPrefs.edit().putString(keyToSave, set.name).putInt("active_image_index", nextImageIndex).apply()
                applicationContext.sendBroadcast(intent)
                previousBitmap = currentBitmap; previousEntry = currentEntry
                imageEntries = newEntries.toMutableList(); currentImageIndex = nextImageIndex; currentEntry = nextEntry; currentBitmap = nextBitmap
                startAnimator()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (visible) {
                if (needsReload || currentBitmap == null) {
                    reloadCurrent()
                } else {
                    draw()
                }
            } else {
                goQuiet()
            }
        }

        /** 非表示になった。アニメーションとコールバックを止め、ビットマップも手放す。 */
        private fun goQuiet() {
            animator?.cancel()
            animator = null
            textAnimator?.cancel()
            textAnimator = null
            clearToolFinder()
            tapHandler.removeCallbacksAndMessages(null)
            textHandler.removeCallbacksAndMessages(null)
            holdRunnable = null
            longPressRunnable = null
            isAnimating = false
            showTextProgress = 0f
            tapCount = 0
            lastTapTime = 0L
            downX = 0f
            downY = 0f
            downTime = 0L
            previousBitmap = null
            previousEntry = null
            currentBitmap = null
            needsReload = true
        }

        private fun reloadCurrent() {
            needsReload = false
            if (!engineVisible) {
                needsReload = true
                return
            }
            if (loadActiveAlbumAndIndex() && imageEntries.isNotEmpty()) {
                currentImageIndex = currentImageIndex.coerceIn(0, imageEntries.size - 1)
                val entry = imageEntries[currentImageIndex]
                currentEntry = entry
                loadAndSetCurrentBitmap(entry) { draw() }
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (loadActiveAlbumAndIndex() && imageEntries.isNotEmpty()) {
                val entry = imageEntries[currentImageIndex]
                currentEntry = entry
                loadAndSetCurrentBitmap(entry) { showSetNameText(); draw() }
            } else draw()
        }

        private fun draw() {
            if (!engineVisible) return
            val canvas: Canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surfaceHolder.lockHardwareCanvas() else surfaceHolder.lockCanvas()
            } catch (e: Exception) { null } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                if (isAnimating && previousBitmap != null && currentBitmap != null) {
                    val progress = animationProgress.coerceIn(0f, 1f)
                    val prevBitmap = previousBitmap!!; val currBitmap = currentBitmap!!
                    val prevSrc = calculateSrcRect(prevBitmap, previousEntry); val prevDest = Rect(0, 0, canvas.width, canvas.height)
                    calculateDestRect(prevSrc, canvas, prevDest)
                    val currSrc = calculateSrcRect(currBitmap, currentEntry); val currDest = Rect(0, 0, canvas.width, canvas.height)
                    calculateDestRect(currSrc, canvas, currDest)
                    prevPaint.alpha = 255; canvas.drawBitmap(prevBitmap, prevSrc, prevDest, prevPaint)
                    val currAlpha = (progress * 255).toInt().coerceIn(0, 255)
                    currPaint.alpha = currAlpha; canvas.drawBitmap(currBitmap, currSrc, currDest, currPaint)
                } else {
                    currentBitmap?.let {
                        val src = calculateSrcRect(it, currentEntry); val dest = Rect(0, 0, canvas.width, canvas.height)
                        calculateDestRect(src, canvas, dest); currPaint.alpha = 255; canvas.drawBitmap(it, src, dest, currPaint)
                    }
                }
                if (showTextProgress > 0f) {
                    val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val activeSetName = getActiveSetName(settingsPrefs) ?: ""
                    if (activeSetName.isNotEmpty()) {
                        val isChatActive = settingsPrefs.getBoolean("is_chat_active", false)
                        val activeSets = getFilteredActiveSets(isChatActive)
                        val totalSets = activeSets.size
                        val setIndex = activeSets.indexOfFirst { it.name == activeSetName } + 1
                        val currentSet = activeSets.find { it.name == activeSetName }
                        val totalImagesInSet = currentSet?.filterImages(DataManager.allImages)?.filter { it.isActive }?.size ?: 0
                        
                        val displayText = String.format(
                            Locale.US,
                            "IMAGE SET [ %02d / %02d ] : %s ( %d )",
                            setIndex, totalSets, activeSetName.uppercase(Locale.US), totalImagesInSet
                        )

                        textPaint.alpha = (showTextProgress * 255).toInt().coerceIn(0, 255)
                        val x = canvas.width / 2f
                        val density = applicationContext.resources.displayMetrics.density
                        val y = canvas.height - (18f * density)
                        canvas.drawText(displayText, x, y, textPaint)
                    }
                }
                toolFinder?.draw(canvas)
            } finally { surfaceHolder.unlockCanvasAndPost(canvas) }
        }

        private fun calculateSrcRect(bitmap: Bitmap, entry: ImageEntry?): Rect {
            val crop = entry?.cropRect
            return if (crop != null) Rect((crop.left * bitmap.width).toInt(), (crop.top * bitmap.height).toInt(), (crop.right * bitmap.width).toInt(), (crop.bottom * bitmap.height).toInt()) else Rect(0, 0, bitmap.width, bitmap.height)
        }

        private fun calculateDestRect(srcRect: Rect, canvas: Canvas, outRect: Rect) {
            val srcWidth = srcRect.width().toFloat(); val srcHeight = srcRect.height().toFloat()
            if (srcWidth <= 0 || srcHeight <= 0) return
            val srcRatio = srcWidth / srcHeight; val canvasRatio = canvas.width.toFloat() / canvas.height.toFloat()
            if (srcRatio > canvasRatio) { val width = (canvas.height * srcRatio).toInt(); val left = (canvas.width - width) / 2; outRect.set(left, 0, left + width, canvas.height) }
            else { val height = (canvas.width / srcRatio).toInt(); val top = (canvas.height - height) / 2; outRect.set(0, top, canvas.width, top + height) }
        }
    }
}

private enum class ToolFinderTarget {
    NONE,
    APP,
    CHAT,
    SECTION
}

private data class ToolFinderOption(
    val section: HomeSection,
    val label: String,
    val angle: Float
)

private class ToolFinderOverlay(
    context: Context,
    touchX: Float,
    touchY: Float,
    surfaceWidth: Int
) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private fun dp(value: Float) = value * density

    private val baseRadius = dp(ToolFinderSpec.BASE_RADIUS_DP)
    private val centerHoleRadius = dp(ToolFinderSpec.CENTER_HOLE_RADIUS_DP)
    private val menuRadius = dp(ToolFinderSpec.MENU_RADIUS_DP)
    private val optionWidth = dp(ToolFinderSpec.OPTION_WIDTH_DP)
    private val optionHeight = dp(ToolFinderSpec.OPTION_HEIGHT_DP)
    private val optionPickRadius = dp(ToolFinderSpec.OPTION_PICK_RADIUS_DP)
    private val maxExtent = dp(ToolFinderSpec.MAX_EXTENT_DP)
    private val maxPickRadius = maxExtent
    private val appColor = Color.parseColor("#D0BCFF")
    private val chatColor = Color.parseColor("#A6F0E4")
    private val appPanelSelected = Color.parseColor("#704F378B")
    private val appPanelIdle = Color.parseColor("#263D2860")
    private val chatPanelSelected = Color.parseColor("#704A766D")
    private val chatPanelIdle = Color.parseColor("#26305A53")
    private val selectedAppArc = Color.parseColor("#FFD0BCFF")
    private val selectedChatArc = Color.parseColor("#FFA6F0E4")
    private val selectedLabel = Color.WHITE
    private val idleOptionLabel = Color.parseColor("#C4CCD7")

    val centerX = clampCenter(touchX, surfaceWidth.toFloat(), dp(ToolFinderSpec.HORIZONTAL_EXTENT_DP))
    val centerY = touchY

    var revealProgress = 0f
    var exitProgress = 0f
    var isClosing = false
        private set
    val isExpanded: Boolean
        get() = expanded

    private var expanded = false
    private var closingSweepFraction = 1f
    private var lastX = centerX
    private var lastY = centerY
    private var selectedTarget = ToolFinderTarget.NONE
    private var selectedSection: HomeSection? = null

    // Fixed angles keep each tool in a predictable position.
    private val options = listOf(
        ToolFinderOption(HomeSection.ALL_IMAGES, "全画像", ToolFinderSpec.OPTION_ANGLE_START),
        ToolFinderOption(HomeSection.IMAGE_SETS, "セット", ToolFinderSpec.OPTION_ANGLE_START + ToolFinderSpec.OPTION_ANGLE_STEP),
        ToolFinderOption(HomeSection.TAGS, "タグ", ToolFinderSpec.OPTION_ANGLE_START + ToolFinderSpec.OPTION_ANGLE_STEP * 2f),
        ToolFinderOption(HomeSection.BUILDER, "生成", ToolFinderSpec.OPTION_ANGLE_START + ToolFinderSpec.OPTION_ANGLE_STEP * 3f),
        ToolFinderOption(HomeSection.DIARY, "日記", ToolFinderSpec.OPTION_ANGLE_START + ToolFinderSpec.OPTION_ANGLE_STEP * 4f),
        ToolFinderOption(HomeSection.SETTINGS, "設定", ToolFinderSpec.OPTION_ANGLE_START + ToolFinderSpec.OPTION_ANGLE_STEP * 5f)
    )

    private val optionIcons = options.associate { option ->
        option.section to ContextCompat.getDrawable(context, option.section.iconRes)
            ?.mutate()
            ?.apply { setTint(ContextCompat.getColor(context, option.section.iconColorRes)) }
    }
    private val appIcon = ContextCompat.getDrawable(context, HomeSection.ALL_IMAGES.iconRes)
        ?.mutate()
        ?.apply { setTint(appColor) }
    private val chatIcon = ContextCompat.getDrawable(context, R.drawable.ic_md3_smart_toy)
        ?.mutate()
        ?.apply { setTint(chatColor) }

    private val bounds = RectF()
    private val optionBounds = RectF()
    private val baseFill = paint(Paint.Style.FILL, Color.parseColor("#E51B202B"))
    private val baseOutline = paint(Paint.Style.STROKE, Color.parseColor("#80D0BCFF"), dp(1.2f))
    private val dividerPaint = paint(Paint.Style.STROKE, Color.parseColor("#55D0BCFF"), dp(1f))
    private val topFill = paint(Paint.Style.FILL, Color.parseColor("#263D2860"))
    private val bottomFill = paint(Paint.Style.FILL, Color.parseColor("#26305A53"))
    private val activeArc = paint(Paint.Style.STROKE, Color.WHITE, dp(3f))
    private val appText = textPaint(appColor, 13f)
    private val chatText = textPaint(chatColor, 13f)
    private val optionText = textPaint(Color.parseColor("#C4CCD7"), 9f)
    private val deadzoneFill = paint(Paint.Style.FILL, Color.parseColor("#F01A202B"))
    private val deadzoneOutline = paint(Paint.Style.STROKE, Color.parseColor("#75D0BCFF"), dp(1f))
    private val deadzoneDot = paint(Paint.Style.FILL, Color.parseColor("#FFD0BCFF"))
    private val archPaint = paint(Paint.Style.STROKE, Color.parseColor("#80A6F0E4"), dp(1f))
    private val guidePaint = paint(Paint.Style.STROKE, Color.parseColor("#5565C9C0"), dp(1f))
    private val optionFill = paint(Paint.Style.FILL, Color.parseColor("#E5232935"))
    private val optionOutline = paint(Paint.Style.STROKE, Color.parseColor("#667D8796"), dp(1f))
    private val selectedOptionFill = paint(Paint.Style.FILL, Color.parseColor("#F038485B"))
    private val selectedOptionOutline = paint(Paint.Style.STROKE, Color.parseColor("#E0A6F0E4"), dp(1.5f))
    private val optionGlow = paint(Paint.Style.FILL, Color.parseColor("#355CE1D8"))
    private val scannerColor = Color.parseColor("#E0A6F0E4")
    private val scannerPaint = paint(Paint.Style.STROKE, scannerColor, dp(2f))

    init {
        bounds.set(centerX - baseRadius, centerY - baseRadius, centerX + baseRadius, centerY + baseRadius)
        optionText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        appText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        chatText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun update(x: Float, y: Float) {
        lastX = x
        lastY = y
        selectedSection = null

        val dx = x - centerX
        val dy = y - centerY
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (distance <= centerHoleRadius || distance > maxPickRadius) {
            selectedTarget = ToolFinderTarget.NONE
            return
        }

        if (!expanded) {
            selectedTarget = when {
                distance > baseRadius -> ToolFinderTarget.NONE
                dy < -centerHoleRadius -> ToolFinderTarget.APP
                dy > centerHoleRadius -> ToolFinderTarget.CHAT
                else -> ToolFinderTarget.NONE
            }
            return
        }

        if (distance <= baseRadius) {
            selectedTarget = when {
                dy < -centerHoleRadius -> ToolFinderTarget.APP
                dy > centerHoleRadius -> ToolFinderTarget.CHAT
                else -> ToolFinderTarget.NONE
            }
            return
        }
        if (dy < -centerHoleRadius && distance < optionPickRadius) {
            selectedTarget = ToolFinderTarget.APP
            return
        }
        if (dy >= -centerHoleRadius || distance < optionPickRadius) {
            selectedTarget = ToolFinderTarget.NONE
            return
        }

        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val option = options.minByOrNull { abs(it.angle - angle) }
        if (option == null || abs(option.angle - angle) > ToolFinderSpec.MAX_OPTION_ANGLE_DELTA) {
            selectedTarget = ToolFinderTarget.NONE
            return
        }

        selectedTarget = ToolFinderTarget.SECTION
        selectedSection = option.section
    }

    fun isAppSelected(): Boolean = selectedTarget == ToolFinderTarget.APP

    fun expand(): Boolean {
        if (expanded || !isAppSelected() || isClosing) {
            return false
        }
        expanded = true
        update(lastX, lastY)
        return true
    }

    fun isChatSelected(): Boolean = selectedTarget == ToolFinderTarget.CHAT

    fun selectedSection(): HomeSection? =
        selectedSection.takeIf { selectedTarget == ToolFinderTarget.SECTION }

    fun beginClosing() {
        closingSweepFraction = easeInOut(revealProgress)
        isClosing = true
    }

    fun draw(canvas: Canvas) {
        val sweepProgress = easeInOut(if (isClosing) exitProgress else revealProgress)
        val clipSave = canvas.save()
        if (isClosing || sweepProgress < 1f) {
            canvas.clipPath(revealPath(sweepProgress))
        }

        drawWheel(canvas)
        if (expanded) {
            drawExpandedTools(canvas)
        }
        drawCenterHole(canvas)
        drawScanner(canvas, sweepProgress)

        canvas.restoreToCount(clipSave)
    }

    private fun easeInOut(progress: Float): Float {
        val value = progress.coerceIn(0f, 1f)
        return value * value * (3f - 2f * value)
    }

    private fun drawWheel(canvas: Canvas) {
        val appSelected = selectedTarget == ToolFinderTarget.APP || selectedTarget == ToolFinderTarget.SECTION
        val chatSelected = selectedTarget == ToolFinderTarget.CHAT
        canvas.drawCircle(centerX, centerY, baseRadius, baseFill)

        topFill.color = if (appSelected) {
            appPanelSelected
        } else {
            appPanelIdle
        }
        bottomFill.color = if (chatSelected) {
            chatPanelSelected
        } else {
            chatPanelIdle
        }
        canvas.drawArc(bounds, 180f, 180f, true, topFill)
        canvas.drawArc(bounds, 0f, 180f, true, bottomFill)
        canvas.drawCircle(centerX, centerY, baseRadius, baseOutline)
        canvas.drawLine(centerX - baseRadius + dp(12f), centerY, centerX + baseRadius - dp(12f), centerY, dividerPaint)

        if (appSelected) {
            activeArc.color = selectedAppArc
            canvas.drawArc(bounds, 184f, 172f, false, activeArc)
        }
        if (chatSelected) {
            activeArc.color = selectedChatArc
            canvas.drawArc(bounds, 4f, 172f, false, activeArc)
        }

        drawIcon(canvas, appIcon, centerX, centerY - dp(61f), dp(if (appSelected) 30f else 26f))
        appText.color = if (appSelected) selectedLabel else appColor
        appText.textSize = scaledDensity * if (appSelected) 15f else 13f
        canvas.drawText("アプリ", centerX, centerY - dp(29f), appText)

        drawIcon(canvas, chatIcon, centerX, centerY + dp(51f), dp(if (chatSelected) 30f else 26f))
        chatText.color = if (chatSelected) selectedLabel else chatColor
        chatText.textSize = scaledDensity * if (chatSelected) 15f else 13f
        canvas.drawText("AIチャット", centerX, centerY + dp(88f), chatText)
    }

    private fun drawExpandedTools(canvas: Canvas) {
        val menuBounds = RectF(
            centerX - menuRadius,
            centerY - menuRadius,
            centerX + menuRadius,
            centerY + menuRadius
        )
        canvas.drawArc(
            menuBounds,
            ToolFinderSpec.OPTION_ANGLE_START,
            ToolFinderSpec.OPTION_ANGLE_STEP * (options.size - 1),
            false,
            archPaint
        )

        options.forEach { option ->
            val radians = Math.toRadians(option.angle.toDouble())
            val directionX = cos(radians).toFloat()
            val directionY = sin(radians).toFloat()
            val innerGuideRadius = baseRadius + dp(2f)
            val outerGuideRadius = menuRadius - optionHeight / 2f - dp(4f)
            canvas.drawLine(
                centerX + directionX * innerGuideRadius,
                centerY + directionY * innerGuideRadius,
                centerX + directionX * outerGuideRadius,
                centerY + directionY * outerGuideRadius,
                guidePaint
            )
            drawOption(canvas, option, centerX + directionX * menuRadius, centerY + directionY * menuRadius)
        }
    }

    private fun drawOption(canvas: Canvas, option: ToolFinderOption, x: Float, y: Float) {
        val selected = selectedTarget == ToolFinderTarget.SECTION && selectedSection == option.section
        val scale = if (selected) 1.1f else 1f
        canvas.save()
        canvas.scale(scale, scale, x, y)
        optionBounds.set(
            x - optionWidth / 2f,
            y - optionHeight / 2f,
            x + optionWidth / 2f,
            y + optionHeight / 2f
        )
        val radius = dp(16f)

        if (selected) {
            canvas.drawCircle(x, y, dp(35f), optionGlow)
            canvas.drawRoundRect(optionBounds, radius, radius, selectedOptionFill)
            canvas.drawRoundRect(optionBounds, radius, radius, selectedOptionOutline)
        } else {
            canvas.drawRoundRect(optionBounds, radius, radius, optionFill)
            canvas.drawRoundRect(optionBounds, radius, radius, optionOutline)
        }

        drawIcon(canvas, optionIcons[option.section], x, y - dp(7f), dp(21f))
        optionText.color = if (selected) selectedLabel else idleOptionLabel
        optionText.textSize = scaledDensity * if (selected) 9.5f else 9f
        canvas.drawText(option.label, x, y + dp(21f), optionText)
        canvas.restore()
    }

    private fun drawCenterHole(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, centerHoleRadius, deadzoneFill)
        canvas.drawCircle(centerX, centerY, centerHoleRadius, deadzoneOutline)
        canvas.drawCircle(centerX, centerY, dp(2f), deadzoneDot)
    }

    private fun drawScanner(canvas: Canvas, progress: Float) {
        val sweepFraction = if (isClosing) closingSweepFraction * (1f - progress) else progress
        scannerPaint.alpha = (Color.alpha(scannerColor) * sweepFraction).toInt().coerceIn(0, 255)
        val startAngle = if (isClosing) {
            ToolFinderSpec.REVEAL_START_ANGLE_DEGREES +
                ToolFinderSpec.FULL_CIRCLE_DEGREES * closingSweepFraction * progress
        } else {
            ToolFinderSpec.REVEAL_START_ANGLE_DEGREES
        }
        val sweepAngle = ToolFinderSpec.FULL_CIRCLE_DEGREES * sweepFraction
        val radius = if (expanded) menuRadius + dp(8f) else baseRadius + dp(8f)
        val scanBounds = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(scanBounds, startAngle, sweepAngle, false, scannerPaint)
    }

    private fun revealPath(progress: Float): Path {
        val path = Path()
        val revealBounds = RectF(
            centerX - maxExtent,
            centerY - maxExtent,
            centerX + maxExtent,
            centerY + maxExtent
        )
        val startAngle = if (isClosing) {
            ToolFinderSpec.REVEAL_START_ANGLE_DEGREES +
                ToolFinderSpec.FULL_CIRCLE_DEGREES * closingSweepFraction * progress
        } else {
            ToolFinderSpec.REVEAL_START_ANGLE_DEGREES
        }
        val sweepFraction = if (isClosing) closingSweepFraction * (1f - progress) else progress
        val sweepAngle = ToolFinderSpec.FULL_CIRCLE_DEGREES * sweepFraction
        path.moveTo(centerX, centerY)
        path.arcTo(revealBounds, startAngle, sweepAngle, false)
        path.close()
        return path
    }

    private fun drawIcon(canvas: Canvas, drawable: Drawable?, x: Float, y: Float, size: Float) {
        if (drawable == null) {
            return
        }
        val halfSize = size / 2f
        drawable.setBounds(
            (x - halfSize).toInt(),
            (y - halfSize).toInt(),
            (x + halfSize).toInt(),
            (y + halfSize).toInt()
        )
        drawable.draw(canvas)
    }

    private fun clampCenter(position: Float, length: Float, extent: Float): Float {
        if (length <= extent * 2f) {
            return length / 2f
        }
        return position.coerceIn(extent, length - extent)
    }

    private fun paint(style: Paint.Style, color: Int, strokeWidth: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            this.color = color
            this.strokeWidth = strokeWidth
        }

    private fun textPaint(color: Int, sizeSp: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = scaledDensity * sizeSp
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
}
