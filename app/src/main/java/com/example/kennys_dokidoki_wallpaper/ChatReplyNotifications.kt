package com.example.kennys_dokidoki_wallpaper

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** メインスレッド専用。推論用の常駐通知とは独立した返信通知。 */
internal object ChatReplyNotifications {
    const val EXTRA_SESSION = "reply_notification_session"
    private const val KEY_ENABLED = "chat_reply_notifications"
    private const val CHANNEL = "character_chat_replies"
    private const val TAG_PREFIX = "chat-reply/"
    private const val NOTICE_ID = 42070
    private val viewers = mutableMapOf<Any, String>()
    private data class Turn(
        val session: String,
        val node: String,
        val image: String?,
        val policy: ChatReplyNoticePolicy = ChatReplyNoticePolicy()
    )
    private var turn: Turn? = null

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun configure(context: Context, setting: ChatReplyNoticePolicy.Setting) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, setting == ChatReplyNoticePolicy.Setting.ON).apply()
        if (setting == ChatReplyNoticePolicy.Setting.OFF) {
            clear(context, TAG_PREFIX)
        }
    }

    fun viewing(context: Context, owner: Any, session: String?) {
        viewers.remove(owner)
        if (session != null) {
            viewers[owner] = session
            clear(context, "$TAG_PREFIX$session/")
        }
    }

    fun leave(owner: Any) {
        viewers.remove(owner)
    }

    fun begin(session: String, node: String, image: String?) {
        turn = Turn(session, node, image)
    }

    fun abandon() {
        turn = null
    }

    fun content(context: Context, node: String, text: String) {
        if (text.isNotBlank()) {
            emit(context, node, ChatReplyNoticePolicy.Event.STARTED)
        }
    }

    fun complete(context: Context, node: String) {
        emit(context, node, ChatReplyNoticePolicy.Event.COMPLETED)
        if (turn?.node == node) {
            turn = null
        }
    }

    fun destination(context: Context): Intent {
        val current = turn ?: return Intent(context, ChatOverlayActivity::class.java)
        return destination(context, current)
    }

    private fun destination(context: Context, target: Turn): Intent =
        Intent(context, ChatOverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SESSION, target.session)
            putExtra("IMAGE_URI", target.image)
        }

    private fun emit(context: Context, node: String, event: ChatReplyNoticePolicy.Event) {
        val target = turn?.takeIf { it.node == node } ?: return
        val visibility = if (target.session in viewers.values) {
            ChatReplyNoticePolicy.Visibility.VIEWING
        } else {
            ChatReplyNoticePolicy.Visibility.AWAY
        }
        val setting = if (enabled(context)) { ChatReplyNoticePolicy.Setting.ON } else { ChatReplyNoticePolicy.Setting.OFF }
        if (!target.policy.accept(event, visibility, setting)) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val name = ChatSessionManager.getSessionName(context, target.session) ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "キャラクターの返信", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "チャットを離れている間の返信開始・完了"
        })
        val intent = destination(context, target).apply {
            data = Uri.Builder().scheme("kennys-chat").authority("reply")
                .appendPath(target.session).appendPath(node).appendPath(event.name).build()
        }
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = if (event == ChatReplyNoticePolicy.Event.STARTED) { "返信が始まりました" } else { "返信が完了しました" }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title).setContentText(name)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // OS設定の変更・権限取消が推論を止めないようにする。
        runCatching { manager.notify("$TAG_PREFIX${target.session}/$node/${event.name}", NOTICE_ID, notification) }
    }

    private fun clear(context: Context, prefix: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true }.forEach {
            manager.cancel(it.tag, it.id)
        }
    }
}
