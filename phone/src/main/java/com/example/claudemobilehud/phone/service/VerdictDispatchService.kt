package com.example.claudemobilehud.phone.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.claudemobilehud.phone.data.ChannelClient
import com.example.claudemobilehud.phone.data.WireErrorException
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.error.SharedWireError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Phase 3 §3.3.5 / AD-16 の短命 FGS-dataSync。
 *
 * アプリプロセス kill 中に通知シェードから verdict (Allow/Deny) が来た時、
 * `PermissionActionReceiver` がこの Service を `startForegroundService` する。
 * 本サービスは:
 *   1. すぐに startForeground (dataSync 通知) で OS 起動制限を満たす
 *   2. extras から baseUrl / token / requestId / behavior を取り出す
 *   3. fresh `ChannelClient` で POST /permission
 *   4. 完了したら通知 cancel + stopSelf
 *
 * NFR-14 (5s 以内) の予算は設計書 §3.3.5.1。
 *
 * **生存 scope**: Service 自身の lifetime に紐づく `serviceScope`。stopSelf 前に
 * 既存 launch が走り続けるが、Service 死で scope.cancel() される。
 */
class VerdictDispatchService : Service() {
    private val log = StructuredLog("channel.verdict")
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inflight: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        log.info("on_create")
    }

    override fun onDestroy() {
        inflight?.cancel()
        serviceScope.cancel()
        log.info("on_destroy")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("Verdict を送信中")
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID)
        val behaviorStr = intent?.getStringExtra(EXTRA_BEHAVIOR)
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL)
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        val notificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1) ?: -1

        if (requestId.isNullOrBlank() || behaviorStr.isNullOrBlank() || baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            log.warn(
                "verdict_dispatch_missing_extras",
                "request_id" to (requestId ?: "null"),
                "behavior" to (behaviorStr ?: "null"),
                "base_url_present" to !baseUrl.isNullOrBlank(),
                "token_present" to !token.isNullOrBlank(),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // 同一 PendingIntent で 2 回叩かれた場合 (theoretical) は既存 inflight を join cancel
        // してから新 launch を走らせる。stopForeground/stopSelf の finally race を避ける。
        // runBlocking は Service の main thread を 1 tick だけ block するが、cancel 直後の
        // finally 完了まで (ms オーダー) なので ANR には至らない。
        inflight?.let { prev ->
            runBlocking { withContext(NonCancellable) { prev.cancelAndJoin() } }
        }
        inflight = serviceScope.launch {
            try {
                // §3.3.5.1 NFR-14 5s 予算: callTimeout=4s で LAN/Tailscale 不通時の deadlock を回避。
                // SSE 用 defaultClient は readTimeout=0 のため別 OkHttpClient を建てる。
                val http = OkHttpClient.Builder()
                    .callTimeout(4, TimeUnit.SECONDS)
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .build()
                val client = ChannelClient(baseUrl, token, http)
                // P3-6: 設計書は PermissionDecision.valueOf() を呼ぶ snippet だが本実装は
                // 例外を起こさない when 比較に変更。verdict 経路は extras が破損していても
                // crash させたくないため (Receiver が enum 名以外を入れる経路は理論上無いが防御)。
                val decision = parseDecision(behaviorStr)
                if (decision == null) {
                    log.warn("verdict_dispatch_bad_behavior", "behavior" to behaviorStr)
                    return@launch
                }
                val result = client.sendPermissionVerdict(requestId, decision)
                val wire = (result.exceptionOrNull() as? WireErrorException)?.wireError
                when {
                    result.isSuccess -> {
                        if (notificationId >= 0) {
                            getSystemService(NotificationManager::class.java)?.cancel(notificationId)
                        }
                        log.info("verdict_dispatch_ok", "request_id" to requestId)
                    }
                    wire is SharedWireError.Connection.AuthFailed -> {
                        log.warn("verdict_dispatch_auth_failed", "request_id" to requestId)
                        // UI で扱えないため通知文言を「再ペアが必要」に書き換える hook は 4c で実装 (§3.7)。
                    }
                    else -> {
                        log.warn(
                            "verdict_dispatch_failed",
                            "request_id" to requestId,
                            "error" to (result.exceptionOrNull()?.message ?: ""),
                        )
                    }
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun parseDecision(raw: String): PermissionDecision? = when (raw) {
        "ALLOW" -> PermissionDecision.ALLOW
        "DENY" -> PermissionDecision.DENY
        else -> null
    }

    private fun startForegroundCompat(status: String) {
        val notification = NotificationFactory.verdictDispatch(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIF_VERDICT_DISPATCH,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_VERDICT_DISPATCH, notification)
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_BEHAVIOR = "behavior"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
