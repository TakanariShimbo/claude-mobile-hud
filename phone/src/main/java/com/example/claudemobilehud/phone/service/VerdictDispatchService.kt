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
 * 短命 FGS-dataSync (docs/03 §3.3.5 / AD-16)。kill 中の通知シェード verdict 経路。
 * NFR-14 (5s) 予算 (§3.3.5.1)、PendingIntent extras (§3.3.5.2)、実装上の不変条件
 * (§3.3.5.4: callTimeout=4s 別 OkHttpClient / 二度叩き cancelAndJoin / parseDecision 防御 /
 * missing extras 早期 stop / AuthFailed log-only) を参照。
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

        // docs/03 §3.3.5.4: 同一 PendingIntent 二度叩きの finally race を防ぐ。
        inflight?.let { prev ->
            runBlocking { withContext(NonCancellable) { prev.cancelAndJoin() } }
        }
        inflight = serviceScope.launch {
            try {
                // docs/03 §3.3.5.4: SSE 用 defaultClient (readTimeout=0) ではなく callTimeout=4s の専用 client。
                val http = OkHttpClient.Builder()
                    .callTimeout(4, TimeUnit.SECONDS)
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .build()
                val client = ChannelClient(baseUrl, token, http)
                // docs/03 §3.3.5.4 (P3-6): valueOf を避けて when で防御。
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
                        // docs/03 §3.3.5.4: 4c+ で通知文言書き換え hook 実装予定。現状は log のみ。
                        log.warn("verdict_dispatch_auth_failed", "request_id" to requestId)
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
