package com.example.claudemobilehud.phone.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.claudemobilehud.phone.MainActivity
import com.example.claudemobilehud.phone.R
import com.example.claudemobilehud.phone.data.model.PendingPermission

/**
 * 通知ビルダ集 (docs/03 §3.6.3 channel id / §3.6.4 extras schema / §3.6.4.1 requestCode 採番)。
 * v1.0 期間中 channel id は変更不可。
 */
object NotificationFactory {
    const val CHANNEL_SERVICE = "claude-mhud-service-v1"
    const val CHANNEL_MIC = "claude-mhud-mic-v1"
    const val CHANNEL_REPLY = "claude-mhud-reply-v1"
    const val CHANNEL_PERMISSION = "claude-mhud-permission-v1"
    const val CHANNEL_GLASS_CONNECTION = "claude-mhud-cxr-v1"
    const val CHANNEL_VERDICT_DISPATCH = "claude-mhud-verdict-v1"

    const val NOTIF_SERVICE = 1
    const val NOTIF_MIC = 2
    const val NOTIF_GLASS_CONNECTION = 3
    const val NOTIF_VERDICT_DISPATCH = 4
    const val NOTIF_REPLY = 100
    const val NOTIF_PERMISSION_BASE = 1000

    const val EXTRA_SESSION_ID = "session_id"
    const val EXTRA_NOTIFICATION_KIND = "notification_kind"
    const val EXTRA_REQUEST_ID = "request_id"
    const val KIND_REPLY = "reply"
    const val KIND_PERMISSION = "permission"

    fun ensureChannels(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Hub 接続",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Hub への常駐接続を維持するための通知" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MIC,
                "マイク使用",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Glass 接続中のマイク確保通知" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GLASS_CONNECTION,
                "Glass 接続",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Rokid Glass との接続を維持するための通知" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VERDICT_DISPATCH,
                "Verdict 送出",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "アプリ kill 中の permission verdict 送出通知 (短命)" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REPLY,
                "返信",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Claude からの返信" },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERMISSION,
                "ツール承認",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Claude からのツール実行承認" },
        )
    }

    fun foreground(context: Context, status: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Claude Mobile HUD")
            .setContentText(status)
            .setOngoing(true)
            .setContentIntent(
                openAppIntent(context, sessionId = null, kind = null, requestId = null, notificationId = NOTIF_SERVICE),
            )
            .build()

    fun mic(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_MIC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Glass 接続中")
            .setContentText("マイクを確保しています")
            .setOngoing(true)
            .setContentIntent(
                openAppIntent(context, sessionId = null, kind = null, requestId = null, notificationId = NOTIF_MIC),
            )
            .build()

    fun glassConnection(context: Context): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_GLASS_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Glass 接続")
            .setContentText("Rokid Glass と通信中")
            .setOngoing(true)
            .setContentIntent(
                openAppIntent(
                    context, sessionId = null, kind = null, requestId = null, notificationId = NOTIF_GLASS_CONNECTION,
                ),
            )
            .build()

    fun verdictDispatch(context: Context, status: String): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_VERDICT_DISPATCH)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Verdict 送信")
            .setContentText(status)
            .setOngoing(true)
            .build()

    fun reply(
        context: Context,
        sessionId: String?,
        text: String,
        notificationId: Int,
    ): android.app.Notification {
        val title = if (sessionId != null) "Claude (${sessionShort(sessionId)})" else "Claude"
        return NotificationCompat.Builder(context, CHANNEL_REPLY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(
                openAppIntent(
                    context = context,
                    sessionId = sessionId,
                    kind = KIND_REPLY,
                    requestId = null,
                    notificationId = notificationId,
                ),
            )
            .build()
    }

    /** docs/03 §3.3.5.2: baseUrl / token を extras に冗長化することで kill 経路 NFR-14 5s 予算を満たす。 */
    fun permission(
        context: Context,
        pending: PendingPermission,
        notificationId: Int,
        baseUrl: String,
        token: String,
    ): android.app.Notification {
        // docs/03 §3.6.4.1: action button の requestCode は notificationId*2 / *2+1 で分離。
        val allow = PendingIntent.getBroadcast(
            context,
            notificationId * 2,
            PermissionActionReceiver.intent(
                context = context,
                requestId = pending.requestId,
                allow = true,
                notificationId = notificationId,
                baseUrl = baseUrl,
                token = token,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deny = PendingIntent.getBroadcast(
            context,
            notificationId * 2 + 1,
            PermissionActionReceiver.intent(
                context = context,
                requestId = pending.requestId,
                allow = false,
                notificationId = notificationId,
                baseUrl = baseUrl,
                token = token,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = buildString {
            appendLine(pending.description)
            if (pending.inputPreview.isNotBlank()) {
                appendLine()
                append(pending.inputPreview.take(400))
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_PERMISSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ツール承認: ${pending.toolName}")
            .setContentText(pending.description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // P3-3: action icon は 0 でも text-only として表示されるが lint warn を消すため流用。
            .addAction(R.drawable.ic_notification, "許可", allow)
            .addAction(R.drawable.ic_notification, "拒否", deny)
            .setAutoCancel(true)
            .setContentIntent(
                openAppIntent(
                    context = context,
                    sessionId = pending.sessionId,
                    kind = KIND_PERMISSION,
                    requestId = pending.requestId,
                    notificationId = notificationId,
                ),
            )
            .build()
    }

    /** docs/03 §3.6.4 extras + §3.6.4.1 採番。requestCode = 呼び側ユニーク notificationId。 */
    private fun openAppIntent(
        context: Context,
        sessionId: String?,
        kind: String?,
        requestId: String?,
        notificationId: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) intent.putExtra(EXTRA_SESSION_ID, sessionId)
        if (kind != null) intent.putExtra(EXTRA_NOTIFICATION_KIND, kind)
        if (requestId != null) intent.putExtra(EXTRA_REQUEST_ID, requestId)
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun sessionShort(id: String): String = id.take(8)
}
