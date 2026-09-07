package com.example.kennys_dokidoki_wallpaper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * ライブ壁紙エンジン。
 *
 *   Prefs / Broadcast ──▶ reloadAlbum() ──▶ decode(IO) ──▶ crossfade ──▶ draw()
 *   Touch ──▶ TapGestureDetector ──▶ executeActionString()
 *
 * 旧実装からの修正:
 *   - draw() の中で毎フレーム getSharedPreferences + 全画像フィルタ + String.format していた
 *       → HUD 文字列はアルバム変更時に 1 回だけ計算し hudText に保持
 *   - 毎フレーム Rect を 4 個 new していた → フィールドで再利用
 *   - loadData() (ファイル読込) を Main スレッドで呼んでいた → IO へ
 *   - 不可視時もアニメーションが継続 → onVisibilityChanged で停止
 *   - 使い終わった Bitmap を recycle せず GC 任せ → フェード完了時に解放
 *   - タップ判定ロジックが ChatOverlayActivity と重複 → TapGestureDetector に統合
 *   - "#8892B0" 等の直書き色 → リソース参照
 */
class MyWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallpaperEngine()

    private companion object {
        const val TAG = "MyWallpaperService"
        const val CROSSFADE_MS = 320L
        const val HUD_FADE_MS = 250L
        const val HUD_HOLD_MS = 2_000L
        const val HUD_FORMAT = "IMAGE SET [ %02d / %02d ] : %s ( %d )"
        const val HUD_PILL_PADDING_DP = 10f
        const val HUD_PILL_RADIUS_DP = 12f
        const val MAX_SHUFFLE_RETRY = 8
        const val ALPHA_OPAQUE = 255
    }

    inner class WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener,
        TapGestureDetector.Callbacks {

        private val engineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private val mainHandler = Handler(Looper.getMainLooper())
        private val gestures = TapGestureDetector(this, mainHandler)
        private val settings: SharedPreferences by lazy {
            getSharedPreferences(PrefFiles.SETTINGS, Context.MODE_PRIVATE)
        }
        private val wallpaperPrefs: SharedPreferences by lazy {
            getSharedPreferences(PrefFiles.WALLPAPER, Context.MODE_PRIVATE)
        }

        // ---- 表示状態 ----
        private var imageEntries: List<ImageEntry> = emptyList()
        private var currentIndex = 0
        private var currentEntry: ImageEntry? = null
        private var previousEntry: ImageEntry? = null
        private var currentBitmap: Bitmap? = null
        private var previousBitmap: Bitmap? = null
        private var isVisible = false
        private var isLoading = false

        // ---- アニメーション ----
        private var crossfade: ValueAnimator? = null
        private var crossfadeProgress = 1f
        private var hudAnimator: ValueAnimator? = null
        private var hudAlpha = 0f
        private var hudText = ""
        private val hideHud = Runnable { animateHud(to = 0f) }

        // ---- 描画リソース (再利用) ----
        private val bitmapPaint = Paint().apply { isFilterBitmap = true; isDither = true }
        private val fadePaint = Paint().apply { isFilterBitmap = true; isDither = true }
        private val srcRect = Rect()
        private val dstRect = Rect()
        private val prevSrcRect = Rect()
        private val prevDstRect = Rect()
        private val hudBounds = Rect()
        private val hudPill = RectF()
        private val density get() = resources.displayMetrics.density
        private val hudTextPaint by lazy {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(applicationContext, R.color.wallpaper_hud_text)
                // sp 指定の dimen は getDimension() で既にスケール済み px が返る
                textSize = resources.getDimension(R.dimen.wallpaper_hud_text_size)
                textAlign = Paint.Align.CENTER
                letterSpacing = 0.08f
            }
        }
        private val hudScrimColor by lazy { ContextCompat.getColor(applicationContext, R.color.wallpaper_hud_scrim) }
        private val hudPillPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = hudScrimColor } }
        private val motionEasing = PathInterpolator(0.4f, 0f, 0.2f, 1f) // MD3 standard easing
        private val letterboxColor by lazy { ContextCompat.getColor(applicationContext, R.color.wallpaper_letterbox) }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WallpaperActions.SIMULATE_TAP -> {
                        val count = intent.getIntExtra(WallpaperActions.EXTRA_TAP_COUNT, 0)
                        if (count > 0) {
                            onTaps(count)
                        }
                    }
                    WallpaperActions.EXECUTE_ACTION -> {
                        intent.getStringExtra(WallpaperActions.EXTRA_ACTION_STRING)?.let { executeActionString(it) }
                    }
                    WallpaperActions.SHOW_SET_NAME -> showHud()
                    WallpaperActions.WALLPAPER_CHANGED -> reloadAlbum(animate = false)
                }
            }
        }

        // ----------------------------------------------------------- lifecycle

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            settings.registerOnSharedPreferenceChangeListener(this)
            wallpaperPrefs.registerOnSharedPreferenceChangeListener(this)
            ContextCompat.registerReceiver(
                applicationContext, receiver, WallpaperActions.filter(), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        override fun onDestroy() {
            super.onDestroy()
            engineScope.cancel()
            gestures.reset()
            mainHandler.removeCallbacksAndMessages(null)
            crossfade?.cancel()
            hudAnimator?.cancel()
            settings.unregisterOnSharedPreferenceChangeListener(this)
            wallpaperPrefs.unregisterOnSharedPreferenceChangeListener(this)
            runCatching { applicationContext.unregisterReceiver(receiver) }
            val oldPrevious = previousBitmap
            val oldCurrent = currentBitmap
            previousBitmap = null
            currentBitmap = null
            recycle(oldPrevious)
            recycle(oldCurrent)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            reloadAlbum(animate = false, thenShowHud = true)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (!visible) {
                crossfade?.end()
                hudAnimator?.cancel()
                mainHandler.removeCallbacks(hideHud)
                hudAlpha = 0f
                return
            }
            draw()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            when (key) {
                PrefKeys.IS_CHAT_ACTIVE -> reloadAlbum(animate = true)
                PrefKeys.ACTIVE_ALBUM,
                PrefKeys.ACTIVE_ALBUM_HOME,
                PrefKeys.ACTIVE_ALBUM_CHAT,
                PrefKeys.ACTIVE_IMAGE_INDEX,
                PrefKeys.LEGACY_ALL_IMAGES,
                PrefKeys.LEGACY_IMAGE_SETS,
                PrefKeys.DATA_REVISION -> reloadAlbum(animate = false)
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            gestures.onTouchEvent(event)
        }

        // ------------------------------------------------------------ gestures

        override fun onTouchStart() = showHud()

        override fun onTaps(count: Int) {
            if (count < TapActions.MIN_TAP_COUNT || count > TapActions.MAX_TAP_COUNT) {
                return
            }
            val action = settings.getString(PrefKeys.actionForTaps(count), null)
                ?: TapActions.defaultForTaps(count)
            executeActionString(action)
        }

        override fun onTapsAndHold(count: Int) {
            val action = settings.getString(PrefKeys.actionForTapsAndHold(count), TapActions.NONE) ?: TapActions.NONE
            executeActionString(action)
        }

        override fun onHold1s() {
            val action = settings.getString(PrefKeys.ACTION_HOLD_1S, TapActions.NONE) ?: TapActions.NONE
            executeActionString(action)
        }

        private fun executeActionString(action: String) {
            TapActions.specificSet(action)?.let { switchToAlbum(it) ; return }
            when (action) {
                TapActions.NEXT_IMAGE -> nextImage()
                TapActions.NEXT_SET -> nextAlbum()
                TapActions.TOGGLE_AI_CHAT -> launchActivity(ChatOverlayActivity::class.java) {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                TapActions.OPEN_APP -> launchActivity(MainActivity::class.java) { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) }
                TapActions.CROP_IMAGE -> currentEntry?.let { entry ->
                    launchActivity(CropHandlerActivity::class.java) {
                        putExtra("SOURCE_URI", entry.uri.toString())
                        putExtra("IMAGE_URI", entry.uri.toString())
                    }
                }
                TapActions.EDIT_TAGS -> currentEntry?.let { entry ->
                    launchActivity(ImageTagEditorActivity::class.java) { putExtra("IMAGE_URI", entry.uri.toString()) }
                }
                TapActions.EDIT_ACTIVE_SET -> activeSetName()?.let { name ->
                    launchActivity(ImageTagEditorActivity::class.java) {
                        putExtra("SET_NAME", name)
                        putExtra("CREATE_NEW_SET", false)
                    }
                }
                else -> Unit
            }
        }

        private fun launchActivity(target: Class<*>, configure: Intent.() -> Unit = {}) {
            try {
                val intent = Intent(applicationContext, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.configure()
                applicationContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "failed to launch ${target.simpleName}", e)
            }
        }

        // ------------------------------------------------------------- albums

        private fun isChatActive(): Boolean = settings.getBoolean(PrefKeys.IS_CHAT_ACTIVE, false)

        private fun activeSetName(): String? {
            val specific = if (isChatActive()) PrefKeys.ACTIVE_ALBUM_CHAT else PrefKeys.ACTIVE_ALBUM_HOME
            return settings.getString(specific, null) ?: settings.getString(PrefKeys.ACTIVE_ALBUM, null)
        }

        private fun activeSets(): List<ImageSet> {
            val chat = isChatActive()
            return DataManager.imageSetList.filter { set ->
                if (!set.isActive) {
                    return@filter false
                }
                when (set.usage) {
                    ImageSetUsage.BOTH -> true
                    ImageSetUsage.CHAT -> chat
                    ImageSetUsage.HOMESCREEN -> !chat
                    else -> false
                }
            }
        }

        private fun entriesOf(set: ImageSet): List<ImageEntry> =
            set.filterImages(DataManager.allImages).filter { it.isActive }

        /**
         * 設定に従ってアルバムと index を読み直し、必要なら画像を差し替える。
         * URI が同じでもクロップ範囲が変わっている可能性があるので entry は常に更新する。
         */
        private fun reloadAlbum(animate: Boolean, thenShowHud: Boolean = false) {
            engineScope.launch {
                withContext(Dispatchers.IO) { DataManager.loadData(this@MyWallpaperService) }

                val setName = activeSetName()
                val set = setName?.let { name -> DataManager.imageSetList.find { it.name == name } }
                val entries = set?.let { entriesOf(it) }.orEmpty()
                if (setName == null || entries.isEmpty()) {
                    imageEntries = emptyList()
                    hudText = ""
                    draw()
                    return@launch
                }

                imageEntries = entries
                currentIndex = settings.getInt(PrefKeys.lastIndexForAlbum(setName), 0)
                    .coerceIn(0, entries.lastIndex)
                refreshHudText(setName, entries.size)

                val entry = entries[currentIndex]
                if (entry.uri == currentEntry?.uri && currentBitmap != null) {
                    currentEntry = entry
                    draw()
                    if (thenShowHud) {
                        showHud()
                    }
                    return@launch
                }

                val bitmap = decode(entry.displayUri) ?: return@launch
                if (animate && currentBitmap != null) {
                    startCrossfade(entry, bitmap)
                } else {
                    val old = currentBitmap
                    currentBitmap = bitmap
                    currentEntry = entry
                    recycle(old)
                    draw()
                }
                if (thenShowHud) {
                    showHud()
                }
            }
        }

        private fun nextImage() {
            if (isLoading || crossfade?.isRunning == true || imageEntries.isEmpty()) {
                return
            }
            val size = imageEntries.size
            val shuffle = settings.getBoolean(PrefKeys.SHUFFLE_IMAGES, false)
            val next = if (shuffle && size > 1) {
                var candidate = currentIndex
                var retry = 0
                while (candidate == currentIndex && retry++ < MAX_SHUFFLE_RETRY) {
                    candidate = (0 until size).random()
                }
                if (candidate == currentIndex) (currentIndex + 1) % size else candidate
            } else {
                (currentIndex + 1) % size
            }
            transitionTo(imageEntries, next)
        }

        private fun nextAlbum() {
            val sets = activeSets()
            if (sets.isEmpty()) {
                return
            }
            val current = sets.indexOfFirst { it.name == activeSetName() }
            val nextSet = sets[(current + 1).mod(sets.size)]
            switchToAlbum(nextSet.name)
        }

        private fun switchToAlbum(setName: String) {
            if (isLoading || crossfade?.isRunning == true) {
                return
            }
            val set = DataManager.imageSetList.find { it.name == setName } ?: return
            val entries = entriesOf(set)
            if (entries.isEmpty()) {
                return
            }
            val index = settings.getInt(PrefKeys.lastIndexForAlbum(setName), 0).coerceIn(0, entries.lastIndex)

            val albumKey = if (isChatActive()) PrefKeys.ACTIVE_ALBUM_CHAT else PrefKeys.ACTIVE_ALBUM_HOME
            settings.edit()
                .putString(albumKey, setName)
                .putInt(PrefKeys.ACTIVE_IMAGE_INDEX, index)
                .putInt(PrefKeys.lastIndexForAlbum(setName), index)
                .apply()
            refreshHudText(setName, entries.size)
            transitionTo(entries, index)
        }

        private fun transitionTo(entries: List<ImageEntry>, index: Int) {
            val entry = entries[index]
            engineScope.launch {
                val bitmap = decode(entry.displayUri) ?: return@launch
                imageEntries = entries
                currentIndex = index
                startCrossfade(entry, bitmap)
            }
        }

        private fun persistIndex() {
            val setName = activeSetName() ?: return
            settings.edit()
                .putInt(PrefKeys.ACTIVE_IMAGE_INDEX, currentIndex)
                .putInt(PrefKeys.lastIndexForAlbum(setName), currentIndex)
                .apply()
            WallpaperActions.notifyWallpaperChanged(applicationContext)
        }

        // ----------------------------------------------------------- crossfade

        private fun startCrossfade(nextEntry: ImageEntry, nextBitmap: Bitmap) {
            crossfade?.cancel()
            val stale = previousBitmap
            previousBitmap = currentBitmap
            previousEntry = currentEntry
            currentBitmap = nextBitmap
            currentEntry = nextEntry
            crossfadeProgress = 0f
            recycle(stale)

            crossfade = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CROSSFADE_MS
                interpolator = motionEasing
                addUpdateListener { crossfadeProgress = it.animatedValue as Float; draw() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = finishCrossfade()
                    override fun onAnimationCancel(animation: Animator) = finishCrossfade()
                })
                start()
            }
            showHud()
        }

        private fun finishCrossfade() {
            crossfadeProgress = 1f
            val done = previousBitmap
            previousBitmap = null
            previousEntry = null
            recycle(done)
            persistIndex()
            draw()
        }

        // ------------------------------------------------------------ decoding

        private suspend fun decode(uri: Uri): Bitmap? {
            isLoading = true
            try {
                val (reqW, reqH) = screenSize()
                return withContext(Dispatchers.IO) {
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    } catch (e: Exception) {
                        Log.e(TAG, "decode failed: $uri", e)
                        null
                    }
                }
            } finally {
                isLoading = false
            }
        }

        private fun screenSize(): Pair<Int, Int> {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.maximumWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
            val fallbackW = resources.displayMetrics.widthPixels
            val fallbackH = resources.displayMetrics.heightPixels
            return (if (w > 0) w else fallbackW) to (if (h > 0) h else fallbackH)
        }

        private fun sampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
            var sample = 1
            if (height > reqH || width > reqW) {
                val halfH = height / 2
                val halfW = width / 2
                while (halfH / sample >= reqH && halfW / sample >= reqW) {
                    sample *= 2
                }
            }
            return sample
        }

        /** 呼び出し側が currentBitmap / previousBitmap から外してから渡すこと */
        private fun recycle(bitmap: Bitmap?) {
            if (bitmap != null && !bitmap.isRecycled && bitmap !== currentBitmap && bitmap !== previousBitmap) {
                bitmap.recycle()
            }
        }

        // ----------------------------------------------------------------- HUD

        private fun refreshHudText(setName: String, imageCount: Int) {
            val sets = activeSets()
            val index = sets.indexOfFirst { it.name == setName } + 1
            hudText = String.format(Locale.US, HUD_FORMAT, index, sets.size, setName.uppercase(Locale.US), imageCount)
        }

        private fun showHud() {
            if (hudText.isEmpty() || !isVisible) {
                return
            }
            mainHandler.removeCallbacks(hideHud)
            if (hudAlpha < 1f) {
                animateHud(to = 1f)
            }
            mainHandler.postDelayed(hideHud, HUD_HOLD_MS)
        }

        private fun animateHud(to: Float) {
            hudAnimator?.cancel()
            hudAnimator = ValueAnimator.ofFloat(hudAlpha, to).apply {
                duration = HUD_FADE_MS
                addUpdateListener { hudAlpha = it.animatedValue as Float; draw() }
                start()
            }
        }

        // ---------------------------------------------------------------- draw

        private fun draw() {
            if (!isVisible && currentBitmap != null) {
                return
            }
            val holder = surfaceHolder
            val canvas: Canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return

            try {
                canvas.drawColor(letterboxColor)

                val current = currentBitmap
                val previous = previousBitmap
                val fading = crossfadeProgress < 1f && previous != null && !previous.isRecycled

                if (fading) {
                    computeRects(previous!!, previousEntry, canvas, prevSrcRect, prevDstRect)
                    bitmapPaint.alpha = ALPHA_OPAQUE
                    canvas.drawBitmap(previous, prevSrcRect, prevDstRect, bitmapPaint)
                }
                if (current != null && !current.isRecycled) {
                    computeRects(current, currentEntry, canvas, srcRect, dstRect)
                    val paint = if (fading) fadePaint else bitmapPaint
                    paint.alpha = if (fading) (crossfadeProgress * ALPHA_OPAQUE).toInt().coerceIn(0, ALPHA_OPAQUE) else ALPHA_OPAQUE
                    canvas.drawBitmap(current, srcRect, dstRect, paint)
                }
                if (hudAlpha > 0f && hudText.isNotEmpty()) {
                    drawHud(canvas)
                }
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }

        private fun drawHud(canvas: Canvas) {
            val pad = HUD_PILL_PADDING_DP * density
            val radius = HUD_PILL_RADIUS_DP * density
            val bottomMargin = resources.getDimension(R.dimen.wallpaper_hud_bottom_margin)

            hudTextPaint.getTextBounds(hudText, 0, hudText.length, hudBounds)
            val cx = canvas.width / 2f
            val baseline = canvas.height - bottomMargin - pad
            hudPill.set(
                cx - hudBounds.width() / 2f - pad * 1.5f,
                baseline + hudBounds.top - pad,
                cx + hudBounds.width() / 2f + pad * 1.5f,
                baseline + hudBounds.bottom + pad
            )

            val alpha = (hudAlpha * ALPHA_OPAQUE).toInt().coerceIn(0, ALPHA_OPAQUE)
            hudPillPaint.alpha = alpha * Color.alpha(hudScrimColor) / ALPHA_OPAQUE
            hudTextPaint.alpha = alpha
            canvas.drawRoundRect(hudPill, radius, radius, hudPillPaint)
            canvas.drawText(hudText, cx, baseline, hudTextPaint)
        }

        /** クロップ範囲 (0..1 の比率) を src に、center-crop した描画先を dst に入れる */
        private fun computeRects(bitmap: Bitmap, entry: ImageEntry?, canvas: Canvas, src: Rect, dst: Rect) {
            val crop = entry?.cropRect
            if (crop != null) {
                src.set(
                    (crop.left * bitmap.width).toInt(),
                    (crop.top * bitmap.height).toInt(),
                    (crop.right * bitmap.width).toInt(),
                    (crop.bottom * bitmap.height).toInt()
                )
            } else {
                src.set(0, 0, bitmap.width, bitmap.height)
            }

            val srcW = src.width().toFloat()
            val srcH = src.height().toFloat()
            if (srcW <= 0f || srcH <= 0f) {
                dst.set(0, 0, canvas.width, canvas.height)
                return
            }
            val srcRatio = srcW / srcH
            val canvasRatio = canvas.width.toFloat() / canvas.height
            if (srcRatio > canvasRatio) {
                val width = (canvas.height * srcRatio).toInt()
                val left = (canvas.width - width) / 2
                dst.set(left, 0, left + width, canvas.height)
            } else {
                val height = (canvas.width / srcRatio).toInt()
                val top = (canvas.height - height) / 2
                dst.set(0, top, canvas.width, top + height)
            }
        }
    }
}
