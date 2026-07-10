package com.example.kennys_dokidoki_wallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * ローカルLLM推論中にプロセスがkillされないよう、フォアグラウンドサービスとして実行するわ。
 * 「AIが考え中...」の通知を分かりやすく表示するの♪
 */
class LlmForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "llm_inference_channel"
        private const val NOTIFICATION_ID = 42069

        fun start(context: Context) {
            val intent = Intent(context, LlmForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LlmForegroundService::class.java)
            context.stopService(intent)
        }

        fun updateNotification(context: Context, text: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(context, text))
        }

        private fun buildNotification(context: Context, contentText: String): Notification {
            createNotificationChannel(context)

            val pendingIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, ChatOverlayActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass) // OS標準 of AI-style icon
                .setContentTitle("🧠 AIキャラクター返信中...")
                .setContentText(contentText)
                .setOngoing(true)
                .setProgress(0, 0, true) // 不定プログレスバー（動いてるの分かりやすい！）
                .setContentIntent(pendingIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW) // 邪魔にならないように
                .build()
        }

        private fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ローカルAI推論",
                    NotificationManager.IMPORTANCE_LOW // 音を鳴らさない
                ).apply {
                    description = "ローカルGPUでAIが考えてるときに表示されるわ"
                    setShowBadge(false)
                }
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
        val notification = buildNotification(this, "モデルを準備してるわ...ちょっと待ってね♪")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
