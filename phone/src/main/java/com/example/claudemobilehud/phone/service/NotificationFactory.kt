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
 * Phase 3 §3.6.3 / §3.6.4 準拠の通知ビルダ集。
 *
 * - channel id は §3.6.3 で凍結 (v1.0 期間中は変更不可)。
 * - PendingIntent extras は §3.6.4 schema (`session_id` / `notification_kind`
 *   / `request_id`)。`MainActivity.onNewIntent` で読まれ該当 session に切替。
 */
object NotificationFactory {
    // §3.6.3 — v1.0 期間中は変更不可。
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

    // PendingIntent extras (§3.6.4)
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

    /**
     * Reply 通知。`notificationId` は session 毎にユニーク (session.hashCode ベース) を呼び側で
     * 採番してもらい、ここでは PendingIntent の requestCode として使う。これにより
     * 異なる session 宛の reply を同時に表示しても PendingIntent.FLAG_UPDATE_CURRENT による
     * extras 上書きが起きない (P1-3 対応)。
     */
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

    /**
     * Permission 通知。
     *
     * **§3.3.5.2 設計トレードオフ** (P1-2 acknowledged):
     *   PendingIntent extras に baseUrl / token を冗長化することで kill 経路でも
     *   NFR-14 5s 予算に収めている。代償として settings 更新後にこの通知をタップすると
     *   旧 token で POST し 401 が返る可能性がある。許容理由:
     *     - permission 通知は表示寿命が短い (Claude 側 timeout で abort される)
     *     - token rotation 直後の outstanding は数件オーダー
     *     - kill 経路を捨てて in-proc に統一すると FR-PH-43 (kill 中通知応答) を失う
     *   将来 settings 更新時に既存通知を再生成する hook を入れれば緩和可能 (4c+ 検討)。
     */
    fun permission(
        context: Context,
        pending: PendingPermission,
        notificationId: Int,
        baseUrl: String,
        token: String,
    ): android.app.Notification {
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
            // action icon は 0 でも text-only button としてレンダリングされるが、
            // P3-3 対応で lint warn を消すため通知アイコンを流用。
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

    /**
     * §3.6.4 schema の extras を伴う MainActivity 起動 PendingIntent。
     * 引数の組み合わせ:
     *   - 通常 (foreground / mic etc.): sessionId/kind/requestId すべて null
     *   - reply 通知: sessionId + kind=KIND_REPLY
     *   - permission 通知: sessionId + kind=KIND_PERMISSION + requestId
     *
     * **P1-3**: requestCode は呼び側で確保したユニーク `notificationId` を使う。
     *   旧版は (sessionId|kind|requestId) のハッシュにしていたが、同じ session の
     *   2 件目 reply が `FLAG_UPDATE_CURRENT` で 1 件目の extras を上書きし、タップ後に
     *   別 session を開くケースがあった。notificationId を採番側 (ChannelService /
     *   NotificationFactory) でユニーク化することで衝突を解消する。
     */
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
