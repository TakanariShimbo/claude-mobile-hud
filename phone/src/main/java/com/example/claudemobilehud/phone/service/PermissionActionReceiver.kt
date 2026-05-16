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
 * Phase 3 §3.8。通知シェードの「許可 / 拒否」ボタンが叩く Receiver。
 *
 * 経路 2 系統:
 *   - **アプリプロセス生存時** (`PhoneApplication.isInitialized`): 直接
 *     `repository.respondPermission` を呼ぶ。通知 cancel もここで。
 *   - **kill 状態**: `VerdictDispatchService` (FGS-dataSync) に委譲。
 *     extras に baseUrl/token を冗長化しているのは DataStore 読み出しを
 *     避け NFR-14 5s 予算に収めるため (§3.3.5.2)。
 *
 * Receiver は短命なので goAsync は使わず `applicationScope` (Application
 * 由来) に suspend 関数を launch する。
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
            // 生存経路: Repository を直接叩く。
            val repo = app.container.repository
            val scope = app.applicationScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { repo.respondPermission(requestId, decision) }
            if (notificationId >= 0) {
                context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
            }
            log.info("verdict_inproc", "request_id" to requestId, "decision" to decision.name)
        } else {
            // kill 経路: FGS-dataSync 委譲。
            if (baseUrl.isBlank() || token.isBlank()) {
                // P2-2: 通知タップは setAutoCancel で消えるが、verdict が Hub に届かないと
                // Glass / Phone UI は気付けない。delegate 不能を repost で見える化する。
                // タップ前の通知が dismiss されていなければそのまま見えるが、AutoCancel で
                // 消えた場合のために改めて post する (channel id は VERDICT_DISPATCH を流用)。
                log.error(
                    "verdict_dispatch_skipped_missing_hub",
                    "request_id" to requestId,
                    "base_url_present" to baseUrl.isNotBlank(),
                    "token_present" to token.isNotBlank(),
                )
                // 通知 id を確保できているならユーザに見える形でリポスト。
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

        /**
         * NotificationFactory.permission から渡す PendingIntent の建設子。
         * baseUrl/token を extras に乗せて kill 経路でも自己完結させる (§3.3.5.2)。
         */
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
