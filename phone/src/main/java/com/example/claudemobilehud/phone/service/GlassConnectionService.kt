package com.example.claudemobilehud.phone.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.claudemobilehud.phone.PhoneApplication
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.Ping
import com.example.claudemobilehud.protocol.SessionClose
import com.example.claudemobilehud.protocol.SessionOpen
import com.example.claudemobilehud.protocol.WireEvent
import com.example.cxrglobal.CXRLink
import com.example.cxrglobal.CxrDefs
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 3 §3.3.3 の Glass FGS-dataSync。4c1 で CXR-L 接続本体を実装。
 *
 * 役割:
 *   - [TokenStore] から CXR-L token を取得し `CXRLink.connect`
 *   - L + BT が両方つながったら glass 側アプリを `appStart` で起動
 *   - 接続後 [SessionOpen] を送り通信を有効化、以降 5s heartbeat ([Ping])
 *   - 受信 payload を [CapsFactory] でデコードし [events] に流す
 *   - 送信は [sender] を `GlassRelay` から呼ぶ
 *
 * ライフサイクル callback (`AppLifecycleController.onFgsLifecycle`) は P2-5
 * 対応のまま維持。Glass 自然切断時は `onGlassDisconnected` で controller に通知。
 */
class GlassConnectionService : Service() {
    private val log = StructuredLog("channel.glass")

    private var cxrLink: CXRLink? = null
    private var lConnected = false
    private var btConnected = false
    private var appStarted = false
    private var sessionOpened = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendWire(Ping(ts = System.currentTimeMillis()))
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // P2-1: companion state は process singleton。新インスタンスを
        // currentInstance に登録しておき、古いインスタンスの onDestroy が
        // 後追いで companion state を消さないようにガードする。
        currentInstance = this
        startForegroundCompat()
        notifyLifecycle(AppLifecycleController.FgsLifecycle.ON_CREATE)
        log.info("on_create")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        runCatching { sendWire(SessionClose(ts = System.currentTimeMillis())) }
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        lConnected = false
        btConnected = false
        appStarted = false
        sessionOpened = false
        // P2-1: 自インスタンスが現役の場合のみ companion state をリセットする。
        // stop/start レースで古いインスタンスが新しい session を kill しないように。
        if (currentInstance === this) {
            currentInstance = null
            _sender.value = null
            _connState.value = GlassCxrState.DISCONNECTED
        }
        notifyLifecycle(AppLifecycleController.FgsLifecycle.ON_DESTROY)
        log.info("on_destroy")
        super.onDestroy()
    }

    /**
     * P2-3: OS kill 後 redeliver で TokenStore を読み直して接続復帰したいため
     * `START_STICKY`。ChannelService と同じ resilience を持たせる。
     * (P3-3 補足: token 更新で running session を再接続する hook は 4c2 検討。)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = TokenStore.token.value
        if (token.isNullOrBlank()) {
            log.warn("glass_no_cxr_token")
            _connState.value = GlassCxrState.DISCONNECTED
            return START_STICKY
        }
        startLink(token)
        return START_STICKY
    }

    private fun startLink(token: String) {
        log.info("glass_start_link", "token_len" to token.length)
        _connState.value = GlassCxrState.CONNECTING
        cxrLink = CXRLink(this).apply {
            configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, GLASS_APP_PKG),
            )
            // P2-2: CXR-L callback は AIDL binder thread。state 更新を main handler に集約し
            // refreshConnState の compare-then-set race を防ぐ。
            setCXRLinkCbk(object : ICXRLinkCbk {
                override fun onCXRLConnected(connected: Boolean) {
                    mainHandler.post {
                        log.info("cxr_l_connected", "connected" to connected)
                        lConnected = connected
                        refreshConnState()
                    }
                }
                override fun onGlassBtConnected(connected: Boolean) {
                    mainHandler.post {
                        log.info("cxr_bt_connected", "connected" to connected)
                        btConnected = connected
                        refreshConnState()
                    }
                }
                override fun onGlassAiAssistStart() {}
                override fun onGlassAiAssistStop() {}
            })
            setCXRCustomCmdCbk(object : ICustomCmdCbk {
                override fun onCustomCmdResult(key: String, payload: ByteArray) {
                    if (key != CHANNEL_FROM_GLASS) return
                    mainHandler.post { handleIncoming(payload) }
                }
            })
            connect(token)
        }
    }

    private fun handleIncoming(payload: ByteArray) {
        val app = applicationContext as? PhoneApplication
        val codec = app?.containerOrNull?.capsCodec
        if (codec == null) {
            log.warn("glass_decode_skip_no_codec")
            return
        }
        val event = codec.decode(payload)
        if (event == null) {
            log.warn("glass_drop_unknown_payload", "bytes" to payload.size)
            return
        }
        // P2-5: tryEmit が false 戻り (subscriber 不在 + buffer 満杯) のときに silent drop を回避。
        if (!_events.tryEmit(event)) {
            log.warn(
                "glass_event_buffer_overflow",
                "type" to event::class.simpleName.orEmpty(),
            )
        }
    }

    private fun refreshConnState() {
        val next = when {
            lConnected && btConnected -> GlassCxrState.CONNECTED
            else -> GlassCxrState.CONNECTING
        }
        // 自然切断: 以前 CONNECTED で今 CONNECTING に落ちた場合は AppLifecycleController に通知。
        val prev = _connState.value
        _connState.value = next
        if (prev == GlassCxrState.CONNECTED && next != GlassCxrState.CONNECTED) {
            val app = applicationContext as? PhoneApplication
            app?.containerOrNull?.lifecycle?.onGlassDisconnected(applicationContext)
        }
        if (next == GlassCxrState.CONNECTED && !appStarted) {
            appStarted = true
            log.info("glass_app_start", "activity" to GLASS_MAIN_ACTIVITY)
            cxrLink?.appStart(GLASS_MAIN_ACTIVITY, object : IGlassAppCbk {
                override fun onOpenAppResult(success: Boolean) {
                    log.info("glass_open_app_result", "success" to success)
                    if (success) onAppOpened()
                }
                override fun onGlassAppResume(resume: Boolean) {
                    log.info("glass_app_resume", "resume" to resume)
                    if (resume) onAppOpened()
                }
            })
        }
    }

    private fun onAppOpened() {
        // P2-4: onOpenAppResult(true) と onGlassAppResume(true) は両方この経路に来る。
        // SessionOpen は 1 度だけ送る (Glass 側 idempotent 期待を避ける)。
        if (sessionOpened) return
        sessionOpened = true
        sendWire(SessionOpen(ts = System.currentTimeMillis()))
        _sender.value = { payload -> sendCustomCmd(payload) }
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun sendWire(event: WireEvent) {
        val app = applicationContext as? PhoneApplication
        val codec = app?.containerOrNull?.capsCodec ?: return
        val payload = runCatching { codec.encode(event) }
            .onFailure { log.warn("glass_encode_failed_in_service", it) }
            .getOrNull() ?: return
        sendCustomCmd(payload)
    }

    private fun sendCustomCmd(payload: ByteArray) {
        val link = cxrLink ?: return
        runCatching { link.sendCustomCmd(CHANNEL_TO_GLASS, payload) }
            .onFailure { log.warn("glass_send_custom_cmd_failed", it, "bytes" to payload.size) }
    }

    private fun notifyLifecycle(event: AppLifecycleController.FgsLifecycle) {
        val app = applicationContext as? PhoneApplication
        if (app?.containerOrNull == null) {
            log.error(
                "fgs_lifecycle_skipped_no_container",
                "event" to event.name,
                "kind" to "glass",
            )
            return
        }
        app.container.lifecycle.onFgsLifecycle(
            AppLifecycleController.FgsKind.GLASS_CONNECTION,
            event,
            applicationContext,
        )
    }

    private fun startForegroundCompat() {
        val notification = NotificationFactory.glassConnection(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationFactory.NOTIF_GLASS_CONNECTION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationFactory.NOTIF_GLASS_CONNECTION, notification)
        }
    }

    companion object {
        private const val GLASS_APP_PKG = "com.example.claudemobilehud.glass"
        private const val GLASS_MAIN_ACTIVITY = "com.example.claudemobilehud.glass.MainActivity"
        const val CHANNEL_TO_GLASS = "rk_custom_client"
        const val CHANNEL_FROM_GLASS = "rk_custom_key"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        private val _connState = MutableStateFlow(GlassCxrState.DISCONNECTED)
        val connState: StateFlow<GlassCxrState> = _connState.asStateFlow()

        private val _events = MutableSharedFlow<WireEvent>(extraBufferCapacity = 64)
        val events: SharedFlow<WireEvent> = _events.asSharedFlow()

        /**
         * Glass へ送信するための callback。null = 未接続。GlassRelay が collect して使う。
         */
        private val _sender = MutableStateFlow<((ByteArray) -> Unit)?>(null)
        val sender: StateFlow<((ByteArray) -> Unit)?> = _sender.asStateFlow()

        /** P2-1: stop/start race のための現役インスタンス pointer。`@Volatile` 必須。 */
        @Volatile
        private var currentInstance: GlassConnectionService? = null
    }
}

/** Glass CXR-L の接続状態。UI / 通知文に使う。 */
enum class GlassCxrState { DISCONNECTED, CONNECTING, CONNECTED }
