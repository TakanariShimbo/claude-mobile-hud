package com.example.claudemobilehud.phone.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.data.ChannelEvent
import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.log.StructuredLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 常駐 FGS-dataSync (docs/03 §3.3.2)。permission 通知の 3 経路 cancel は §3.3.2.1、
 * cold-start gap 対策は §3.3.2.2 / §5.3.1、`START_STICKY` 選択理由は §3.3.2.3 を参照。
 */
class ChannelService : Service() {
    private val log = StructuredLog("channel.service")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationFactory.ensureChannels(this)
        startForegroundCompat("起動中...")
        cancelStaleStartupPermissionNotifications()
        observerJob = scope.launch { observe() }
        log.info("on_create")
    }

    private fun cancelStaleStartupPermissionNotifications() {
        val notifMgr = getSystemService(NotificationManager::class.java) ?: return
        val active = notifMgr.activeNotifications ?: return
        for (sbn in active) {
            if (sbn.notification.channelId == NotificationFactory.CHANNEL_PERMISSION) {
                notifMgr.cancel(sbn.id)
                log.info("permission_cleared_on_startup", "notif_id" to sbn.id)
            }
        }
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        log.info("on_destroy")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun observe() {
        val app = applicationContext as? PhoneApplication ?: return
        val repo = app.container.repository
        val settingsFlow = repo.settings
        val notifMgr = getSystemService(NotificationManager::class.java)

        scope.launch {
            repo.connectivity.collectLatest { conn ->
                startForegroundCompat(connectionLabel(conn))
            }
        }
        // docs/03 §3.3.2.1: 3 経路 cancel を pendingPermissions の diff に集約。
        scope.launch {
            var lastIds = emptySet<String>()
            repo.pendingPermissions.collect { current ->
                val currentIds = current.mapTo(mutableSetOf()) { it.requestId }
                val removed = lastIds - currentIds
                for (requestId in removed) {
                    val notifId = NotificationFactory.NOTIF_PERMISSION_BASE + requestId.hashCode()
                    notifMgr?.cancel(notifId)
                    log.info(
                        "permission_canceled",
                        "request_id" to requestId,
                        "notif_id" to notifId,
                    )
                }
                lastIds = currentIds
            }
        }
        repo.events.collectLatest { event ->
            when (event) {
                is ChannelEvent.Reply -> notifyReply(notifMgr, event, repo)
                is ChannelEvent.PermissionRequested -> notifyPermission(
                    notifMgr = notifMgr,
                    event = event,
                    baseUrl = settingsFlow.value.baseUrl,
                    token = settingsFlow.value.token,
                )
                is ChannelEvent.Sent -> Unit
            }
        }
    }

    private fun notifyReply(notifMgr: NotificationManager?, event: ChannelEvent.Reply, repo: ChannelRepository) {
        // docs/03 §3.6.4.1: notifId = NOTIF_REPLY + sessionId.hashCode() (session 単位)。
        val notifId = NotificationFactory.NOTIF_REPLY + (event.sessionId?.hashCode() ?: 0)
        notifMgr?.notify(
            notifId,
            NotificationFactory.reply(this, event.sessionId, event.text, notifId),
        )
        log.info("reply_notified", "chat_id" to event.chatId, "notif_id" to notifId)
    }

    private fun notifyPermission(
        notifMgr: NotificationManager?,
        event: ChannelEvent.PermissionRequested,
        baseUrl: String,
        token: String,
    ) {
        // docs/03 §3.6.4.1: id = NOTIF_PERMISSION_BASE + requestId.hashCode() (request 単位)。
        val id = NotificationFactory.NOTIF_PERMISSION_BASE + event.pending.requestId.hashCode()
        notifMgr?.notify(
            id,
            NotificationFactory.permission(
                context = this,
                pending = event.pending,
                notificationId = id,
                baseUrl = baseUrl,
                token = token,
            ),
        )
        log.info("permission_notified", "request_id" to event.pending.requestId)
    }

    private fun startForegroundCompat(status: String) {
        val notification = NotificationFactory.foreground(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIF_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_SERVICE, notification)
        }
    }

    private fun connectionLabel(status: ConnectivityState): String = when (status) {
        ConnectivityState.Idle -> "未設定"
        ConnectivityState.Connecting -> "接続中..."
        ConnectivityState.Open -> "接続済み"
        is ConnectivityState.Failed -> "再接続待ち"
        ConnectivityState.AuthFailed -> "token が無効"
    }
}
