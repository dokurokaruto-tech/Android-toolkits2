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
import android.graphics.Rect
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

class MyWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine {
        return MyEngine()
    }

    inner class MyEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val engineJob = Job()
        private val engineScope = CoroutineScope(Dispatchers.Main + engineJob)
        private var isLoading = false

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
            engineJob.cancel()
            val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            settingsPrefs.unregisterOnSharedPreferenceChangeListener(this)
            
            val wallpaperPrefs = getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)
            wallpaperPrefs.unregisterOnSharedPreferenceChangeListener(this)
            
            applicationContext.unregisterReceiver(tapReceiver)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == "active_album_name" || key == "active_album_name_homescreen" || key == "active_album_name_chat" || key == "is_chat_active" || key == "active_image_index" || key == "all_images" || key == "image_sets") {
                Handler(Looper.getMainLooper()).post {
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
                                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                val action = prefs.getString("action_tap_2_hold", "NONE") ?: "NONE"
                                executeActionString(action)
                                tapCount = 0
                            }
                            tapHandler.postDelayed(holdRunnable!!, holdTimeout)
                        } else if (tapCount == 2 && (downTime - lastTapTime) <= tapTimeout) {
                            holdRunnable = Runnable {
                                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                                val action = prefs.getString("action_tap_3_hold", "NONE") ?: "NONE"
                                executeActionString(action)
                                tapCount = 0
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
                        val dx = it.x - downX
                        val dy = it.y - downY
                        val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                        if (distance > TOUCH_TOLERANCE) {
                            holdRunnable?.let { r -> tapHandler.removeCallbacks(r); holdRunnable = null }
                            longPressRunnable?.let { r -> tapHandler.removeCallbacks(r); longPressRunnable = null }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holdRunnable?.let { r -> tapHandler.removeCallbacks(r); holdRunnable = null }
                        longPressRunnable?.let { r -> tapHandler.removeCallbacks(r); longPressRunnable = null }

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

        private fun showSetNameText() {
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
            val intent = Intent(applicationContext, ChatOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            applicationContext.startActivity(intent)
        }

        private fun openApp() {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
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

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (loadActiveAlbumAndIndex() && imageEntries.isNotEmpty()) {
                val entry = imageEntries[currentImageIndex]
                currentEntry = entry
                loadAndSetCurrentBitmap(entry) { showSetNameText(); draw() }
            } else draw()
        }

        private fun draw() {
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
