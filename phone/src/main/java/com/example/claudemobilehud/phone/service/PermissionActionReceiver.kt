package com.example.claudemobilehud.phone.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.PermissionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知シェードの「許可 / 拒否」ボタン Receiver (docs/03 §3.8)。in-proc / kill 経路の判定は
 * §3.8.1、Hub credentials missing 時の repost は §3.8.2 を参照。
 */
class PermissionActionReceiver : BroadcastReceiver() {
    private val log = StructuredLog("channel.verdict")

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL).orEmpty()
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val decision = if (allow) PermissionDecision.ALLOW else PermissionDecision.DENY

        val app = context.applicationContext as? PhoneApplication
        if (app != null && app.containerOrNull != null) {
            val repo = app.container.repository
            val scope = app.applicationScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { repo.respondPermission(requestId, decision) }
            if (notificationId >= 0) {
                context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
            }
            log.info("verdict_inproc", "request_id" to requestId, "decision" to decision.name)
        } else {
            // docs/03 §3.8.2: Hub credentials missing 時の repost。
            if (baseUrl.isBlank() || token.isBlank()) {
                log.error(
                    "verdict_dispatch_skipped_missing_hub",
                    "request_id" to requestId,
                    "base_url_present" to baseUrl.isNotBlank(),
                    "token_present" to token.isNotBlank(),
                )
                if (notificationId >= 0) {
                    val notif = NotificationFactory.verdictDispatch(
                        context,
                        "再ペアが必要です (Hub の token が見つかりません)",
                    )
                    context.getSystemService(android.app.NotificationManager::class.java)
                        ?.notify(notificationId, notif)
                }
                return
            }
            val serviceIntent = Intent(context, VerdictDispatchService::class.java).apply {
                putExtra(VerdictDispatchService.EXTRA_REQUEST_ID, requestId)
                putExtra(VerdictDispatchService.EXTRA_BEHAVIOR, decision.name)
                putExtra(VerdictDispatchService.EXTRA_BASE_URL, baseUrl)
                putExtra(VerdictDispatchService.EXTRA_TOKEN, token)
                putExtra(VerdictDispatchService.EXTRA_NOTIFICATION_ID, notificationId)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
            log.info("verdict_dispatch_started", "request_id" to requestId, "decision" to decision.name)
        }
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_ALLOW = "allow"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val EXTRA_BASE_URL = "base_url"
        private const val EXTRA_TOKEN = "token"

        /** NotificationFactory.permission から渡す PendingIntent の builder (§3.3.5.2)。 */
        fun intent(
            context: Context,
            requestId: String,
            allow: Boolean,
            notificationId: Int,
            baseUrl: String,
            token: String,
        ): Intent = Intent(context, PermissionActionReceiver::class.java).apply {
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_ALLOW, allow)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_BASE_URL, baseUrl)
            putExtra(EXTRA_TOKEN, token)
        }
    }
}
