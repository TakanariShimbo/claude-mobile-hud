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
 * Phase 3 §3.3.2 常駐 FGS-dataSync。
 *
 * 役割:
 *   - `ChannelRepository.connectivity` を購読し常駐通知のテキストを更新
 *   - `ChannelRepository.events` を購読し reply / permission 通知を post
 *   - POC との差分: MicForegroundService を直接 start/stop しない
 *     (AppLifecycleController 経由のみ)
 *
 * `applicationContext` 経由で `PhoneApplication.container` から
 * Repository / Settings を取得。Service プロセスが Application 死亡時に起動する
 * paranoid シナリオは Phase 4 範囲外 (POC でもケアしていない)。
 */
class ChannelService : Service() {
    private val log = StructuredLog("channel.service")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // P1-1 defensive: Application.onCreate が先行する契約だが、START_STICKY による
        // 再起動経路で Application 再初期化が遅延した場合に通知が silent fail するのを防ぐ。
        // ensureChannels は idempotent。
        NotificationFactory.ensureChannels(this)
        startForegroundCompat("起動中...")
        // P2-1 of review: cold-start gap 対策。Application 死亡中に発生した permission の
        // 通知が shade に残っていて、起動時には `_pendingPermissions` が空で start するので
        // 後続の diff collector では cancel されない (lastIds=empty / current=empty で
        // removed が常に empty)。channel = PERMISSION の通知を一旦全 cancel し、Hub 側の
        // 再 push (FR-HU-14: snapshot 後に個別 permission を続けて送る) で必要なものは
        // 再 notify されるので、shade の幽霊通知が確実に消える。
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

    /**
     * P3-4 注釈: `START_STICKY` を選ぶ理由は、ChannelService は Hub 接続維持が役割で、
     * intent 自体に処理対象データを持たない (PhoneApplication.onCreate が再起動経路で先に
     * 走るため Repository も再構築済み)。redeliver する command は無い。kill 直後に
     * Application が再 init される際は ensureChannels も onCreate で idempotent 呼び出し。
     */
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
        // permission 通知の cancel: pendingPermissions の diff を取り、消えた id の
        // 通知を NotificationManager.cancel する。これで以下の経路を統一処理:
        //   - 個別 verdict 成功 (VerdictDispatchService が extras 経由で cancel するのと、
        //     PermissionActionReceiver が optimistic pre-dispatch cancel するのに加えた
        //     3 経路目。すべて idempotent なので重複 OK)
        //   - PermissionAbort 受信
        //   - permission_snapshot で空集合 reconcile (Hub 再起動経路、FR-HU-15)
        // ChannelService が死んでた間の差分は startup の cancelStaleStartupPermissionNotifications
        // で別途処理する (P2-1 cold-start gap 対策)。
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
                is ChannelEvent.Sent -> Unit // UI 内通知のみ。OS 通知不要。
            }
        }
    }

    private fun notifyReply(notifMgr: NotificationManager?, event: ChannelEvent.Reply, repo: ChannelRepository) {
        // P1-3: session 毎にユニークな notification id にすることで、別 session の reply が
        // 同時に shade に並んだとき PendingIntent extras が上書きされる問題を解消する。
        // 同一 session の 2 件目は既存通知を update (NOTIF_REPLY_BASE + session.hashCode).
        // sessionId が null (broadcast 系) は 0 にフォールバック。
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
        // notification id は request_id hash で session 横断ユニーク化。
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
