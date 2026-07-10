package com.example.kennys_dokidoki_wallpaper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.util.ArrayList

/**
 * 錬成（画像生成）をバックグラウンドで安全に続けるためのフォアグラウンドサービスよ！
 * これがあれば、画面を閉じてもロックしても、ノアちゃんが裏で頑張り続けられるわ♪
 */
class GenerationService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "generation_channel"
        private const val NOTIFICATION_ID = 54321
        
        // インテント用キー
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_NEGATIVE_PROMPT = "extra_negative_prompt"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_SAMPLER = "extra_sampler"
        const val EXTRA_BATCH_COUNT = "extra_batch_count"
        const val EXTRA_APPLIED_TAGS = "extra_applied_tags"

        fun start(context: Context, intent: Intent) {
            intent.setClass(context, GenerationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // 端末がスリープしてもCPUを動かし続けるためのWakeLockよ！
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KennyWallpaper:GenerationWakeLock").apply {
            acquire(10 * 60 * 1000L) // 最大10分間（適宜延長されるわ）
        }

        startForeground(NOTIFICATION_ID, buildNotification("錬成を開始するわよ！っ！"))
        
        // 進捗を監視して通知を更新するわ
        serviceScope.launch {
            GenerationProgressManager.state.collectLatest { state ->
                if (state.isGenerating) {
                    val text = if (state.totalBatch > 1) {
                        "錬成中... (${state.currentBatch}/${state.totalBatch}) - ${(state.progress * 100).toInt()}%"
                    } else {
                        "錬成中... ${(state.progress * 100).toInt()}%"
                    }
                    updateNotification(text, (state.progress * 100).toInt())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
        val negativePrompt = intent.getStringExtra(EXTRA_NEGATIVE_PROMPT) ?: ""
        val width = intent.getIntExtra(EXTRA_WIDTH, 720)
        val height = intent.getIntExtra(EXTRA_HEIGHT, 1280)
        val steps = intent.getIntExtra(EXTRA_STEPS, 20)
        val sampler = intent.getStringExtra(EXTRA_SAMPLER) ?: "Euler a"
        val batchCount = intent.getIntExtra(EXTRA_BATCH_COUNT, 1)
        val appliedTags = intent.getStringArrayListExtra(EXTRA_APPLIED_TAGS)?.toSet() ?: emptySet()

        if (prompt.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 実際の錬成処理を非同期で開始するわ！
        serviceScope.launch {
            try {
                var successCount = 0
                GenerationProgressManager.startGeneration(batchMode = true, total = batchCount)

                for (i in 1..batchCount) {
                    // 中止チェック
                    if (GenerationProgressManager.shouldInterrupt) {
                        Log.d("GenerationService", "Interrupted by user")
                        break
                    }

                    GenerationProgressManager.updateBatchProgress(i, batchCount)
                    
                    val success = StabilityManager.generateImage(
                        this@GenerationService,
                        prompt,
                        negativePrompt,
                        width = width,
                        height = height,
                        steps = steps,
                        batchCount = 1,
                        samplerName = sampler,
                        appliedTags = appliedTags
                    )

                    if (success) successCount++
                }

                Log.d("GenerationService", "Batch completed. Success: $successCount/$batchCount")
            } catch (e: Exception) {
                Log.e("GenerationService", "Generation loop failed", e)
            } finally {
                GenerationProgressManager.endGeneration(force = true)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun buildNotification(contentText: String, progress: Int = 0): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, GenerationProgressActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(this, 10, Intent(GenerationProgressActivity.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("🎨 絶賛錬成中！")
            .setContentText(contentText)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "中止", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "画像錬成の進行状況",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "バックグラウンドでの画像生成の状態を表示するわ"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
