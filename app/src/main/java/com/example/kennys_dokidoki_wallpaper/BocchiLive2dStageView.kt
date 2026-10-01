package com.example.kennys_dokidoki_wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * SOUND VOLTEX 風サイバーステージと選択中キャラクターを描く背景 View。
 *
 *   +---------------------------------------------------+
 *   | [7] NEMSYS Cyber-Visor HUD Frame & Telemetry      |
 *   | [6] Floating Prism Particles & Hex Touch Bursts   |
 *   | [5] Selected Member 28x36 ArtMesh + face motion   |
 *   | [4] Counter-Rotating NEMSYS Rings & 48-Band EQ    |
 *   | [3] VOL-L (Cyan) & VOL-R (Magenta) Laser Beams    |
 *   | [2] 3D Perspective Hexagon Grid Floor & Tunnel    |
 *   | [1] Deep Space Gradient & Section-Reactive Nebula |
 *   +---------------------------------------------------+
 */
class BocchiLive2dStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private var selectedCharacter = HomeStageCharacter.BOCCHI
    private var activeSection = HomeSection.ALL_IMAGES
    private val physics = BocchiPhysicsEngine()
    private val kitaPhysics = KitaPhysicsEngine()
    private var currentPose = BocchiPose()
    private var kitaPose = KitaPose()

    private var bodyBitmap: Bitmap? = null
    private var kitaBodyBitmap: Bitmap? = null
    private val faceBitmaps = mutableMapOf<BocchiExpression, Bitmap>()

    private val bodyVerts = FloatArray((BocchiLive2dPolicy.MESH_COLS + 1) * (BocchiLive2dPolicy.MESH_ROWS + 1) * 2)
    private val echoVerts = FloatArray(bodyVerts.size)
    private val faceVerts = FloatArray((BocchiLive2dPolicy.FACE_COLS + 1) * (BocchiLive2dPolicy.FACE_ROWS + 1) * 2)

    private var running = false
    private var lastFrameNanos = 0L
    private var stageTimeSec = 0f
    private var gridScrollPhase = 0f

    private var charLeft = 0f
    private var charTop = 0f
    private var charWidth = 1f
    private var charHeight = 1f

    private var accentColor = COLOR_BOCCHI_PINK
    private var targetAccentColor = COLOR_BOCCHI_PINK
    private val characterAccentColor: Int
        get() = when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> COLOR_BOCCHI_PINK
            HomeStageCharacter.KITA -> KitaLive2dPolicy.ACCENT_COLOR
        }

    private val bursts = Array(MAX_BURSTS) { HexBurst() }
    private var burstCursor = 0

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val laserGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val eqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val echoCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(COLOR_NEON_CYAN, PorterDuff.Mode.SRC_ATOP)
    }
    private val echoPinkPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(COLOR_BOCCHI_PINK, PorterDuff.Mode.SRC_ATOP)
    }
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_HUD_TEXT
        textSize = 22f
        letterSpacing = 0.12f
        isFakeBoldText = true
    }

    private val dashOuter = DashPathEffect(floatArrayOf(28f, 18f, 8f, 18f), 0f)
    private val dashInner = DashPathEffect(floatArrayOf(14f, 14f), 0f)
    private val hexPath = Path()
    private val starPath = Path()
    private val stringPath = Path()

    init {
        loadAssets()
    }

    fun startStage() {
        if (running) {
            return
        }
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopStage() {
        if (!running) {
            return
        }
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    internal fun setCharacter(character: HomeStageCharacter) {
        if (selectedCharacter == character) {
            return
        }
        selectedCharacter = character
        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> physics.onSectionChange(activeSection)
            HomeStageCharacter.KITA -> kitaPhysics.onSectionChange(activeSection)
        }
        targetAccentColor = sectionAccentColor(activeSection)
        echoPinkPaint.colorFilter = PorterDuffColorFilter(characterAccentColor, PorterDuff.Mode.SRC_ATOP)
        invalidate()
    }

    fun onCarouselScroll(deltaPx: Float, viewWidth: Float) {
        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> physics.onScroll(deltaPx, viewWidth)
            HomeStageCharacter.KITA -> kitaPhysics.onScroll(deltaPx, viewWidth)
        }
        val norm = if (viewWidth > 0f) {
            abs(deltaPx / viewWidth)
        } else {
            0f
        }
        gridScrollPhase = (gridScrollPhase + norm * 0.45f) % 1f
    }

    fun onSectionSelect(section: HomeSection) {
        activeSection = section
        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> physics.onSectionChange(section)
            HomeStageCharacter.KITA -> kitaPhysics.onSectionChange(section)
        }
        targetAccentColor = sectionAccentColor(section)
    }

    private fun sectionAccentColor(section: HomeSection): Int {
        val sectionColor = ContextCompat.getColor(context, section.iconColorRes)
        return if (selectedCharacter == HomeStageCharacter.KITA) {
            blendColor(KitaLive2dPolicy.ACCENT_COLOR, sectionColor, KITA_SECTION_BLEND)
        } else {
            sectionColor
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startStage()
    }

    override fun onDetachedFromWindow() {
        stopStage()
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) {
            return
        }
        val dt = if (lastFrameNanos == 0L) {
            DEFAULT_DT
        } else {
            ((frameTimeNanos - lastFrameNanos) * NANOS_TO_SEC).coerceIn(MIN_DT, MAX_DT)
        }
        lastFrameNanos = frameTimeNanos
        stageTimeSec += dt
        gridScrollPhase = (gridScrollPhase + dt * GRID_SPEED) % 1f
        accentColor = blendColor(accentColor, targetAccentColor, dt * COLOR_LERP_SPEED)

        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> currentPose = physics.step(dt)
            HomeStageCharacter.KITA -> {
                kitaPose = kitaPhysics.step(dt)
                currentPose = kitaPose.stagePose()
            }
        }
        stepBursts(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event)
        }
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val tx = event.x
        val ty = event.y
        val u = (tx - charLeft) / charWidth
        val v = (ty - charTop) / charHeight
        val normX = tx / w
        val normY = ty / h
        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> {
                val zone = BocchiLive2dPolicy.hitZone(u, v)
                physics.onTapZone(zone, normX, normY)
                spawnBurst(tx, ty, zone)
            }
            HomeStageCharacter.KITA -> {
                val zone = KitaLive2dPolicy.hitZone(u, v)
                kitaPhysics.onTapZone(zone, normX, normY)
                val burstZone = when (zone) {
                    KitaHitZone.HEAD -> BocchiHitZone.HEAD_PANIC
                    KitaHitZone.GUITAR -> BocchiHitZone.GUITAR_STRUM
                    KitaHitZone.STAGE -> BocchiHitZone.STAGE_BURST
                }
                spawnBurst(tx, ty, burstZone)
            }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            return
        }
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(COLOR_BG_TOP, COLOR_BG_MID, COLOR_BG_HORIZON, COLOR_BG_BOTTOM),
            floatArrayOf(0f, 0.42f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }

        drawBackdrop(canvas, w, h)
        drawPerspectiveGrid(canvas, w, h)
        drawValkyrieLasers(canvas, w, h, currentPose)
        drawNemsysRings(canvas, w, h, currentPose)
        when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> drawBocchiLive2d(canvas, w, h, currentPose)
            HomeStageCharacter.KITA -> drawKitaLive2d(canvas, w, h, kitaPose)
        }
        drawFloatingPrisms(canvas, w, h)
        drawTouchBursts(canvas)
        drawSdvxHudFrame(canvas, w, h, currentPose)
    }

    private fun loadAssets() {
        bodyBitmap = decodeAsset(BocchiLive2dPolicy.BODY_ASSET)
        kitaBodyBitmap = decodeAsset(KitaLive2dPolicy.BODY_ASSET)
        for (expr in BocchiExpression.entries) {
            val path = expr.assetName ?: continue
            val bmp = decodeAsset(path) ?: continue
            faceBitmaps[expr] = bmp
        }
    }

    private fun decodeAsset(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 左上：選択中キャラクター×機能色のネビュラ発光
        val pinkRadius = max(w, h) * 0.48f
        nebulaPaint.shader = RadialGradient(
            w * 0.26f, h * 0.30f, pinkRadius,
            withAlpha(accentColor, 95), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.26f, h * 0.30f, pinkRadius, nebulaPaint)

        // 右上：シアンキューブ色のネビュラ発光
        val cyanRadius = max(w, h) * 0.46f
        nebulaPaint.shader = RadialGradient(
            w * 0.76f, h * 0.28f, cyanRadius,
            withAlpha(COLOR_NEON_CYAN, 85), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(w * 0.76f, h * 0.28f, cyanRadius, nebulaPaint)
    }

    private fun drawPerspectiveGrid(canvas: Canvas, w: Float, h: Float) {
        val horizonY = h * HORIZON_RATIO
        val vanishX = w * (0.50f + currentPose.laserTilt * 0.08f)

        // 放射パースライン
        gridPaint.color = withAlpha(COLOR_NEON_CYAN, 48)
        gridPaint.strokeWidth = 1.8f
        for (i in -GRID_RAYS..GRID_RAYS) {
            val bottomX = vanishX + i * (w * 0.15f)
            canvas.drawLine(vanishX, horizonY, bottomX, h, gridPaint)
        }

        // 前進する水平パースグリッド
        for (row in 0 until GRID_ROWS) {
            val t = ((row.toFloat() / GRID_ROWS) + gridScrollPhase) % 1f
            val persp = t * t
            val y = horizonY + persp * (h - horizonY)
            val alpha = (28 + (persp * 95f).toInt()).coerceIn(0, 255)
            gridPaint.color = withAlpha(characterAccentColor, alpha)
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // 背景に浮かぶSDVX風ヘキサゴンタイル
        val hexRadius = w * 0.055f
        for (idx in 0 until HEX_TILE_COUNT) {
            val col = (idx % 4)
            val row = (idx / 4)
            val cx = w * (0.14f + col * 0.24f + (row % 2) * 0.08f)
            val cy = h * (0.14f + row * 0.16f)
            val pulse = 0.5f + 0.5f * sin(stageTimeSec * 3.2f + idx * 1.1f)
            val alpha = (18 + (pulse * 52f).toInt()).coerceIn(0, 255)
            gridPaint.color = if (idx % 2 == 0) {
                withAlpha(COLOR_NEON_CYAN, alpha)
            } else {
                withAlpha(characterAccentColor, alpha)
            }
            drawPolygon(canvas, cx, cy, hexRadius, 6, 0f, gridPaint)
        }
    }

    private fun drawValkyrieLasers(canvas: Canvas, w: Float, h: Float, pose: BocchiPose) {
        val tilt = pose.laserTilt * w * 0.12f
        val beatPulse = 1f + abs(pose.beatBounce) * 0.45f

        // VOL-L シアンレーザー
        val lx0 = -w * 0.04f
        val ly0 = h * 0.11f
        val lx1 = w * 0.96f + tilt
        val ly1 = h * 0.64f
        drawLaserBeam(canvas, lx0, ly0, lx1, ly1, COLOR_NEON_CYAN, beatPulse)

        // VOL-R キャラクターアクセントレーザー
        val rx0 = w * 1.04f
        val ry0 = h * 0.09f
        val rx1 = w * 0.04f + tilt
        val ry1 = h * 0.66f
        drawLaserBeam(canvas, rx0, ry0, rx1, ry1, characterAccentColor, beatPulse)
    }

    private fun drawLaserBeam(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color: Int,
        pulse: Float
    ) {
        laserGlowPaint.color = withAlpha(color, 42)
        laserGlowPaint.strokeWidth = 22f * pulse
        canvas.drawLine(x0, y0, x1, y1, laserGlowPaint)

        laserGlowPaint.color = withAlpha(color, 110)
        laserGlowPaint.strokeWidth = 8f * pulse
        canvas.drawLine(x0, y0, x1, y1, laserGlowPaint)

        laserGlowPaint.color = withAlpha(Color.WHITE, 195)
        laserGlowPaint.strokeWidth = 2.6f
        canvas.drawLine(x0, y0, x1, y1, laserGlowPaint)
    }

    private fun drawNemsysRings(canvas: Canvas, w: Float, h: Float, pose: BocchiPose) {
        val cx = w * 0.50f + pose.bodyAngleX * w * 0.015f
        val cy = h * 0.35f + pose.beatBounce * h * 0.008f
        val outerR = min(w, h) * 0.41f
        val midR = outerR * 0.80f
        val innerR = outerR * 0.61f

        // 外周の回転テックリング
        canvas.save()
        canvas.rotate(stageTimeSec * 14f, cx, cy)
        ringPaint.pathEffect = dashOuter
        ringPaint.strokeWidth = 3.5f
        ringPaint.color = withAlpha(COLOR_NEON_CYAN, 130)
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.restore()

        // 中間の逆回転リング
        canvas.save()
        canvas.rotate(-stageTimeSec * 20f, cx, cy)
        ringPaint.pathEffect = dashInner
        ringPaint.strokeWidth = 3.0f
        ringPaint.color = withAlpha(characterAccentColor, 135)
        canvas.drawCircle(cx, cy, midR, ringPaint)
        canvas.restore()

        // 内側のイエローキューブ色オクタゴン
        ringPaint.pathEffect = null
        ringPaint.strokeWidth = 2.2f
        ringPaint.color = withAlpha(COLOR_CUBE_YELLOW, 90)
        drawPolygon(canvas, cx, cy, innerR, 8, stageTimeSec * 0.25f, ringPaint)

        // 48バンド放射スペクトラムイコライザ
        eqPaint.strokeWidth = 4.2f
        val baseR = outerR * 1.04f
        val maxBar = outerR * (0.14f + abs(pose.armStrum) * 0.06f)
        for (band in 0 until EQ_BAND_COUNT) {
            val ang = (2f * PI.toFloat() * band) / EQ_BAND_COUNT + stageTimeSec * 0.18f
            val wave = abs(sin(band * 0.65f + stageTimeSec * 6.5f))
            val barLen = maxBar * (0.25f + 0.75f * wave)
            val cosA = cos(ang)
            val sinA = sin(ang)
            eqPaint.color = if (band % 2 == 0) {
                withAlpha(COLOR_NEON_CYAN, 155)
            } else {
                withAlpha(characterAccentColor, 155)
            }
            canvas.drawLine(
                cx + cosA * baseR,
                cy + sinA * baseR,
                cx + cosA * (baseR + barLen),
                cy + sinA * (baseR + barLen),
                eqPaint
            )
        }
    }

    private fun drawBocchiLive2d(canvas: Canvas, w: Float, h: Float, pose: BocchiPose) {
        val body = bodyBitmap ?: return

        val aspect = body.width.toFloat() / body.height.toFloat()
        charHeight = h * CHAR_HEIGHT_RATIO
        charWidth = charHeight * aspect
        if (charWidth > w * MAX_CHAR_WIDTH_RATIO) {
            charWidth = w * MAX_CHAR_WIDTH_RATIO
            charHeight = charWidth / aspect
        }
        charLeft = (w - charWidth) * 0.5f + w * CHAR_X_BIAS
        charTop = h * CHAR_TOP_RATIO

        BocchiLive2dPolicy.fillBodyMesh(pose, charLeft, charTop, charWidth, charHeight, bodyVerts)

        // Pass 1: サイバーリムエコー（ぼっち崩壊時は色収差ズレが増폭）
        val echoOffset = (4.5f + pose.glitchIntensity * 16f)
        shiftVerts(bodyVerts, echoVerts, -echoOffset, 0f)
        echoCyanPaint.alpha = (55 + (pose.glitchIntensity * 110f).toInt()).coerceIn(0, 255)
        canvas.drawBitmapMesh(
            body,
            BocchiLive2dPolicy.MESH_COLS,
            BocchiLive2dPolicy.MESH_ROWS,
            echoVerts,
            0,
            null,
            0,
            echoCyanPaint
        )

        shiftVerts(bodyVerts, echoVerts, echoOffset, 0f)
        echoPinkPaint.alpha = (55 + (pose.glitchIntensity * 110f).toInt()).coerceIn(0, 255)
        canvas.drawBitmapMesh(
            body,
            BocchiLive2dPolicy.MESH_COLS,
            BocchiLive2dPolicy.MESH_ROWS,
            echoVerts,
            0,
            null,
            0,
            echoPinkPaint
        )

        // Pass 2: メインの28x36変形キャラクターメッシュ
        meshPaint.alpha = 255
        canvas.drawBitmapMesh(
            body,
            BocchiLive2dPolicy.MESH_COLS,
            BocchiLive2dPolicy.MESH_ROWS,
            bodyVerts,
            0,
            null,
            0,
            meshPaint
        )

        // Pass 3: 表情パッチ（10x8サブメッシュ）を同一の変形関数で重ねる
        val faceBmp = faceBitmaps[pose.expression]
        if (faceBmp != null) {
            BocchiLive2dPolicy.fillFaceMesh(pose, charLeft, charTop, charWidth, charHeight, faceVerts)
            canvas.drawBitmapMesh(
                faceBmp,
                BocchiLive2dPolicy.FACE_COLS,
                BocchiLive2dPolicy.FACE_ROWS,
                faceVerts,
                0,
                null,
                0,
                meshPaint
            )
        }

        // Pass 4: 視線追従アイキャッチ・髪飾りキューブ反射・ギター弦エフェクト
        drawLive2dOverlays(canvas, pose)
    }

    private fun drawKitaLive2d(canvas: Canvas, w: Float, h: Float, pose: KitaPose) {
        val body = kitaBodyBitmap ?: return
        val aspect = body.width.toFloat() / body.height.toFloat()
        charHeight = h * KITA_CHAR_HEIGHT_RATIO
        charWidth = charHeight * aspect
        if (charWidth > w * MAX_CHAR_WIDTH_RATIO) {
            charWidth = w * MAX_CHAR_WIDTH_RATIO
            charHeight = charWidth / aspect
        }
        charLeft = (w - charWidth) * 0.5f
        charTop = h * KITA_CHAR_TOP_RATIO

        KitaLive2dPolicy.fillBodyMesh(pose, charLeft, charTop, charWidth, charHeight, bodyVerts)

        val echoOffset = 3.5f + abs(pose.guitarRock) * 2.5f
        shiftVerts(bodyVerts, echoVerts, -echoOffset, 0f)
        echoCyanPaint.alpha = 74
        canvas.drawBitmapMesh(
            body,
            KitaLive2dPolicy.MESH_COLS,
            KitaLive2dPolicy.MESH_ROWS,
            echoVerts,
            0,
            null,
            0,
            echoCyanPaint
        )

        shiftVerts(bodyVerts, echoVerts, echoOffset, 0f)
        echoPinkPaint.alpha = 72
        canvas.drawBitmapMesh(
            body,
            KitaLive2dPolicy.MESH_COLS,
            KitaLive2dPolicy.MESH_ROWS,
            echoVerts,
            0,
            null,
            0,
            echoPinkPaint
        )

        meshPaint.alpha = 255
        canvas.drawBitmapMesh(
            body,
            KitaLive2dPolicy.MESH_COLS,
            KitaLive2dPolicy.MESH_ROWS,
            bodyVerts,
            0,
            null,
            0,
            meshPaint
        )
        drawKitaOverlays(canvas, pose)
    }

    private fun drawKitaOverlays(canvas: Canvas, pose: KitaPose) {
        if (pose.eyeOpen > 0.66f) {
            val gazeU = pose.eyeBallX * 0.004f
            val gazeV = pose.eyeBallY * 0.003f
            val (leftX, leftY) = kitaScreenPoint(
                KitaLive2dPolicy.EYE_LEFT_U + gazeU,
                KitaLive2dPolicy.EYE_V + gazeV,
                pose
            )
            val (rightX, rightY) = kitaScreenPoint(
                KitaLive2dPolicy.EYE_RIGHT_U + gazeU,
                KitaLive2dPolicy.EYE_V + gazeV,
                pose
            )
            val sparkleSize = charWidth * (0.008f + 0.002f * abs(sin(stageTimeSec * 4.4f)))
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = Color.WHITE
            drawStarSparkle(canvas, leftX, leftY, sparkleSize, fxPaint)
            drawStarSparkle(canvas, rightX, rightY, sparkleSize, fxPaint)
        } else {
            drawKitaBlink(canvas, pose)
        }

        val (tailX, tailY) = kitaScreenPoint(0.545f + pose.sidePonytail * 0.025f, 0.215f, pose)
        val hairGlint = charWidth * 0.010f * (0.7f + 0.3f * sin(stageTimeSec * 3.8f))
        fxPaint.style = Paint.Style.FILL
        fxPaint.color = withAlpha(KitaLive2dPolicy.ACCENT_COLOR, 185)
        drawStarSparkle(canvas, tailX, tailY, hairGlint, fxPaint)

        val (bridgeX, bridgeY) = kitaScreenPoint(
            KitaLive2dPolicy.GUITAR_BRIDGE_U,
            KitaLive2dPolicy.GUITAR_BRIDGE_V,
            pose
        )
        val (nutX, nutY) = kitaScreenPoint(
            KitaLive2dPolicy.GUITAR_NUT_U,
            KitaLive2dPolicy.GUITAR_NUT_V,
            pose
        )
        val strumAmp = abs(pose.armStrum) * charWidth * 0.010f
        if (strumAmp > 0.5f) {
            val middleX = (bridgeX + nutX) * 0.5f
            val middleY = (bridgeY + nutY) * 0.5f + sin(stageTimeSec * 42f) * strumAmp
            stringPath.reset()
            stringPath.moveTo(bridgeX, bridgeY)
            stringPath.quadTo(middleX, middleY, nutX, nutY)
            fxPaint.style = Paint.Style.STROKE
            fxPaint.strokeWidth = 2.2f
            fxPaint.color = withAlpha(KitaLive2dPolicy.GUITAR_COLOR, 190)
            canvas.drawPath(stringPath, fxPaint)
        }

        if (pose.expression == KitaExpression.SPARKLE) {
            val (sparkleX, sparkleY) = kitaScreenPoint(0.575f, 0.155f, pose)
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = withAlpha(COLOR_CUBE_YELLOW, 225)
            drawStarSparkle(canvas, sparkleX, sparkleY, charWidth * 0.025f, fxPaint)
        }
    }

    private fun drawKitaBlink(canvas: Canvas, pose: KitaPose) {
        val closed = ((1f - pose.eyeOpen) * 1.35f).coerceIn(0f, 1f)
        if (closed <= 0.08f) {
            return
        }
        val fillAlpha = (closed * 255f).toInt().coerceIn(0, 255)
        val halfWidth = charWidth * KitaLive2dPolicy.EYE_WIDTH * 0.55f
        val halfHeight = charHeight * KitaLive2dPolicy.EYE_HEIGHT * 0.55f
        val gazeU = pose.eyeBallX * 0.003f
        val gazeV = pose.eyeBallY * 0.002f
        val eyeCenters = arrayOf(KitaLive2dPolicy.EYE_LEFT_U, KitaLive2dPolicy.EYE_RIGHT_U)

        fxPaint.style = Paint.Style.FILL
        fxPaint.color = withAlpha(KitaLive2dPolicy.SKIN_COLOR, fillAlpha)
        for (eyeU in eyeCenters) {
            val (eyeX, eyeY) = kitaScreenPoint(eyeU + gazeU, KitaLive2dPolicy.EYE_V + gazeV, pose)
            canvas.drawOval(
                eyeX - halfWidth,
                eyeY - halfHeight * 0.7f,
                eyeX + halfWidth,
                eyeY + halfHeight * 0.7f,
                fxPaint
            )

            if (closed > 0.45f) {
                stringPath.reset()
                stringPath.moveTo(eyeX - halfWidth, eyeY)
                stringPath.quadTo(eyeX, eyeY + halfHeight * 0.45f, eyeX + halfWidth, eyeY)
                fxPaint.style = Paint.Style.STROKE
                fxPaint.strokeWidth = max(1.2f, charWidth * 0.006f)
                fxPaint.strokeCap = Paint.Cap.ROUND
                fxPaint.color = withAlpha(KitaLive2dPolicy.EYE_LASH_COLOR, fillAlpha)
                canvas.drawPath(stringPath, fxPaint)
                fxPaint.style = Paint.Style.FILL
            }
        }
    }

    private fun kitaScreenPoint(u: Float, v: Float, pose: KitaPose): Pair<Float, Float> {
        val (deformedU, deformedV) = KitaLive2dPolicy.deformUv(u, v, pose)
        return (charLeft + deformedU * charWidth) to (charTop + deformedV * charHeight)
    }

    private fun drawLive2dOverlays(canvas: Canvas, pose: BocchiPose) {
        // 開眼時は瞳の上に視線追従するキラキラハイライトを描画
        if (pose.eyeOpen > 0.65f && pose.expression != BocchiExpression.BOCCHI_PANIC) {
            val gazeShiftU = pose.eyeBallX * 0.006f
            val gazeShiftV = pose.eyeBallY * 0.005f
            val (lx, ly) = screenPoint(
                BocchiLive2dPolicy.EYE_LEFT_U + gazeShiftU,
                BocchiLive2dPolicy.EYE_LEFT_V + gazeShiftV,
                pose
            )
            val (rx, ry) = screenPoint(
                BocchiLive2dPolicy.EYE_RIGHT_U + gazeShiftU,
                BocchiLive2dPolicy.EYE_RIGHT_V + gazeShiftV,
                pose
            )
            val sparkleSize = charWidth * (0.011f + 0.003f * abs(sin(stageTimeSec * 5f)))
            fxPaint.style = Paint.Style.FILL
            fxPaint.color = Color.WHITE
            drawStarSparkle(canvas, lx - sparkleSize * 0.8f, ly - sparkleSize * 0.6f, sparkleSize, fxPaint)
            drawStarSparkle(canvas, rx - sparkleSize * 0.8f, ry - sparkleSize * 0.6f, sparkleSize, fxPaint)
        }

        // 青と黄色のキューブ髪飾りのキラリ反射
        val (bx, by) = screenPoint(BocchiLive2dPolicy.CUBE_BLUE_U, BocchiLive2dPolicy.CUBE_BLUE_V, pose)
        val (yx, yy) = screenPoint(BocchiLive2dPolicy.CUBE_YELLOW_U, BocchiLive2dPolicy.CUBE_YELLOW_V, pose)
        val cubeGlint = charWidth * 0.014f * (0.6f + 0.4f * sin(stageTimeSec * 4.2f))
        fxPaint.color = withAlpha(COLOR_NEON_CYAN, 180)
        drawStarSparkle(canvas, bx, by, cubeGlint, fxPaint)
        fxPaint.color = withAlpha(COLOR_CUBE_YELLOW, 180)
        drawStarSparkle(canvas, yx, yy, cubeGlint * 0.9f, fxPaint)

        // レスポール・カスタムの弦振動エフェクト
        val (gx0, gy0) = screenPoint(BocchiLive2dPolicy.GUITAR_BRIDGE_U, BocchiLive2dPolicy.GUITAR_BRIDGE_V, pose)
        val (gx1, gy1) = screenPoint(BocchiLive2dPolicy.GUITAR_NUT_U, BocchiLive2dPolicy.GUITAR_NUT_V, pose)
        val strumAmp = abs(pose.armStrum) * charWidth * 0.012f
        if (strumAmp > 0.5f) {
            val midX = (gx0 + gx1) * 0.5f
            val midY = (gy0 + gy1) * 0.5f + sin(stageTimeSec * 45f) * strumAmp
            stringPath.reset()
            stringPath.moveTo(gx0, gy0)
            stringPath.quadTo(midX, midY, gx1, gy1)
            fxPaint.style = Paint.Style.STROKE
            fxPaint.strokeWidth = 2.4f
            fxPaint.color = withAlpha(COLOR_NEON_CYAN, 175)
            canvas.drawPath(stringPath, fxPaint)
        }

        // ぼっち崩壊時のグリッチ走査線
        if (pose.glitchIntensity > 0f) {
            fxPaint.style = Paint.Style.FILL
            val barCount = 6
            for (i in 0 until barCount) {
                val ratio = ((i * 0.17f + stageTimeSec * 3.7f) % 1f)
                val gy = charTop + charHeight * (0.08f + ratio * 0.65f)
                val gh = 4f + (i % 3) * 3f
                fxPaint.color = if (i % 2 == 0) {
                    withAlpha(COLOR_NEON_CYAN, (pose.glitchIntensity * 150f).toInt())
                } else {
                    withAlpha(COLOR_BOCCHI_PINK, (pose.glitchIntensity * 150f).toInt())
                }
                canvas.drawRect(charLeft, gy, charLeft + charWidth, gy + gh, fxPaint)
            }
        }
    }

    private fun drawFloatingPrisms(canvas: Canvas, w: Float, h: Float) {
        fxPaint.style = Paint.Style.FILL
        for (i in 0 until PRISM_COUNT) {
            val speed = 0.06f + (i % 4) * 0.022f
            val progress = 1f - ((stageTimeSec * speed + i * 0.13f) % 1f)
            val px = w * ((i * 0.19f + 0.07f) % 0.92f) + sin(stageTimeSec * 1.8f + i) * 18f
            val py = h * progress
            val size = 5f + (i % 3) * 3.5f
            val alpha = (sin(progress * PI.toFloat()) * 165f).toInt().coerceIn(0, 255)
            fxPaint.color = when (i % 3) {
                0 -> withAlpha(COLOR_NEON_CYAN, alpha)
                1 -> withAlpha(characterAccentColor, alpha)
                else -> withAlpha(COLOR_CUBE_YELLOW, alpha)
            }
            drawPolygon(canvas, px, py, size, 4, stageTimeSec + i, fxPaint)
        }
    }

    private fun drawTouchBursts(canvas: Canvas) {
        ringPaint.pathEffect = null
        for (burst in bursts) {
            if (!burst.active) {
                continue
            }
            val progress = (burst.elapsed / BURST_DURATION_SEC).coerceIn(0f, 1f)
            val alpha = ((1f - progress) * 220f).toInt().coerceIn(0, 255)
            val radius = 24f + progress * 165f
            ringPaint.strokeWidth = (1f - progress) * 6f + 1.5f
            ringPaint.color = withAlpha(burst.color, alpha)
            drawPolygon(canvas, burst.x, burst.y, radius, 6, progress * 1.2f, ringPaint)
        }
    }

    private fun drawSdvxHudFrame(canvas: Canvas, w: Float, h: Float, pose: BocchiPose) {
        // 上部サイバーバイザーライン
        val topLineY = h * 0.095f
        hudLinePaint.color = withAlpha(COLOR_NEON_CYAN, 130)
        canvas.drawLine(w * 0.05f, topLineY, w * 0.42f, topLineY, hudLinePaint)
        hudLinePaint.color = withAlpha(characterAccentColor, 130)
        canvas.drawLine(w * 0.58f, topLineY, w * 0.95f, topLineY, hudLinePaint)

        // ステータスバッジ表示
        val modeLabel = when (selectedCharacter) {
            HomeStageCharacter.BOCCHI -> when (pose.expression) {
                BocchiExpression.BOCCHI_PANIC -> "CREW: HITORI GOTOH // PANIC GLITCH!"
                BocchiExpression.AWAKENED_GROOVE -> "CREW: HITORI GOTOH // GUITAR HERO"
                BocchiExpression.HAPPY_SMILE -> "CREW: HITORI GOTOH // KESSOKU LIVE"
                else -> "CREW: HITORI GOTOH // LIVE2D 150BPM"
            }
            HomeStageCharacter.KITA -> when (pose.expression) {
                BocchiExpression.AWAKENED_GROOVE -> "CREW: IKUYO KITA // VOCAL GUITAR"
                BocchiExpression.HAPPY_SMILE -> "CREW: IKUYO KITA // KITA-ON!"
                else -> "CREW: IKUYO KITA // LIVE2D 150BPM"
            }
        }
        hudTextPaint.color = withAlpha(COLOR_HUD_TEXT, 190)
        canvas.drawText(modeLabel, w * 0.06f, topLineY - 12f, hudTextPaint)

        // 下部カルーセルデッキのサイバーフレーム
        val deckTopY = h * 0.715f
        val deckBottomY = h * 0.915f
        hudLinePaint.color = withAlpha(COLOR_NEON_CYAN, 115)
        canvas.drawLine(w * 0.06f, deckTopY, w * 0.94f, deckTopY, hudLinePaint)
        hudLinePaint.color = withAlpha(characterAccentColor, 115)
        canvas.drawLine(w * 0.12f, deckBottomY, w * 0.88f, deckBottomY, hudLinePaint)
    }

    private fun screenPoint(u: Float, v: Float, pose: BocchiPose): Pair<Float, Float> {
        val (du, dv) = BocchiLive2dPolicy.deformUv(u, v, pose)
        return (charLeft + du * charWidth) to (charTop + dv * charHeight)
    }

    private fun shiftVerts(src: FloatArray, dst: FloatArray, dx: Float, dy: Float) {
        var i = 0
        while (i < src.size) {
            dst[i] = src[i] + dx
            dst[i + 1] = src[i + 1] + dy
            i += 2
        }
    }

    private fun drawPolygon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        sides: Int,
        rotationRad: Float,
        paint: Paint
    ) {
        hexPath.reset()
        for (i in 0 until sides) {
            val ang = rotationRad + (2f * PI.toFloat() * i) / sides
            val px = cx + cos(ang) * radius
            val py = cy + sin(ang) * radius
            if (i == 0) {
                hexPath.moveTo(px, py)
            } else {
                hexPath.lineTo(px, py)
            }
        }
        hexPath.close()
        canvas.drawPath(hexPath, paint)
    }

    private fun drawStarSparkle(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
        starPath.reset()
        val inner = radius * 0.28f
        for (i in 0 until 8) {
            val r = if (i % 2 == 0) {
                radius
            } else {
                inner
            }
            val ang = (PI.toFloat() * 0.25f) * i
            val px = cx + cos(ang) * r
            val py = cy + sin(ang) * r
            if (i == 0) {
                starPath.moveTo(px, py)
            } else {
                starPath.lineTo(px, py)
            }
        }
        starPath.close()
        canvas.drawPath(starPath, paint)
    }

    private fun spawnBurst(x: Float, y: Float, zone: BocchiHitZone) {
        val b = bursts[burstCursor]
        burstCursor = (burstCursor + 1) % MAX_BURSTS
        b.active = true
        b.x = x
        b.y = y
        b.elapsed = 0f
        b.color = when (zone) {
            BocchiHitZone.HEAD_PANIC -> characterAccentColor
            BocchiHitZone.GUITAR_STRUM -> when (selectedCharacter) {
                HomeStageCharacter.BOCCHI -> COLOR_CUBE_YELLOW
                HomeStageCharacter.KITA -> KitaLive2dPolicy.GUITAR_COLOR
            }
            BocchiHitZone.STAGE_BURST -> COLOR_NEON_CYAN
        }
    }

    private fun stepBursts(dt: Float) {
        for (burst in bursts) {
            if (!burst.active) {
                continue
            }
            burst.elapsed += dt
            if (burst.elapsed >= BURST_DURATION_SEC) {
                burst.active = false
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val clamped = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }

    private fun blendColor(from: Int, to: Int, factor: Float): Int {
        val t = factor.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    private class HexBurst(
        var active: Boolean = false,
        var x: Float = 0f,
        var y: Float = 0f,
        var elapsed: Float = 0f,
        var color: Int = COLOR_NEON_CYAN
    )

    private companion object {
        const val NANOS_TO_SEC = 1e-9f
        const val DEFAULT_DT = 0.016f
        const val MIN_DT = 0.001f
        const val MAX_DT = 0.050f
        const val GRID_SPEED = 0.28f
        const val COLOR_LERP_SPEED = 4.5f

        const val HORIZON_RATIO = 0.68f
        const val CHAR_TOP_RATIO = 0.045f
        const val CHAR_HEIGHT_RATIO = 0.76f
        const val KITA_CHAR_TOP_RATIO = 0.105f
        const val KITA_CHAR_HEIGHT_RATIO = 0.72f
        const val KITA_SECTION_BLEND = 0.38f
        const val MAX_CHAR_WIDTH_RATIO = 0.96f
        const val CHAR_X_BIAS = 0.02f

        const val GRID_RAYS = 8
        const val GRID_ROWS = 10
        const val HEX_TILE_COUNT = 12
        const val EQ_BAND_COUNT = 48
        const val PRISM_COUNT = 16
        const val MAX_BURSTS = 6
        const val BURST_DURATION_SEC = 0.55f

        const val COLOR_BG_TOP = 0xFF060512.toInt()
        const val COLOR_BG_MID = 0xFF120A28.toInt()
        const val COLOR_BG_HORIZON = 0xFF1D0E38.toInt()
        const val COLOR_BG_BOTTOM = 0xFF070514.toInt()

        const val COLOR_NEON_CYAN = 0xFF00E5FF.toInt()
        const val COLOR_BOCCHI_PINK = HOME_BOCCHI_ACCENT_COLOR
        const val COLOR_CUBE_YELLOW = 0xFFFFD54F.toInt()
        const val COLOR_HUD_TEXT = 0xFFD8F6FF.toInt()
    }
}
