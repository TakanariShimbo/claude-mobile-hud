package com.example.claudemobilehud.glass.glass

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.example.claudemobilehud.glass.log.StructuredLog
import com.example.claudemobilehud.protocol.ChatMessagePayload
import com.example.claudemobilehud.protocol.CurrentSessionEvent
import com.example.claudemobilehud.protocol.CurrentState
import com.example.claudemobilehud.protocol.ErrorEvent
import com.example.claudemobilehud.protocol.GestureKind
import com.example.claudemobilehud.protocol.Hello
import com.example.claudemobilehud.protocol.InputTextOnly
import com.example.claudemobilehud.protocol.ListeningCancel
import com.example.claudemobilehud.protocol.MessagesEvent
import com.example.claudemobilehud.protocol.NotificationEvent
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.PermissionVerdictEvent
import com.example.claudemobilehud.protocol.Ping
import com.example.claudemobilehud.protocol.SelectSession
import com.example.claudemobilehud.protocol.SessionClose
import com.example.claudemobilehud.protocol.SessionList
import com.example.claudemobilehud.protocol.SessionOpen
import com.example.claudemobilehud.protocol.SessionSummaryPayload
import com.example.claudemobilehud.protocol.WireEvent
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Glass 側の CXR エンドポイント (docs/03 §4.2)。Phone からの wire event を内部
 * StateFlow に反映、Glass → Phone は send 系メソッドで送る。
 *
 * atomicity (NFR-13 / docs/03 §4.2.1):
 * lastSeenStateSeq で CurrentState を stale ドロップ、InputTextOnly が先行到着した
 * 場合は pendingInputText に 1 件 stash して後続 CurrentState 受信時に適用する。
 *
 * JVM テストは `handleWireEvent` を直接呼べる (CXR binder 非依存)。
 */
object GlassBridge {
    private val log = StructuredLog("channel.glass.bridge")
    private const val PING_TIMEOUT_MS = 12_000L

    // main thread の init() で書かれ、binder thread (status callback / send 系) で読まれる。
    @Volatile
    private var bridge: CXRServiceBridge? = null
    private val capsFactory: CapsFactoryImpl = CapsFactoryImpl()

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen.asStateFlow()

    private val _phoneState = MutableStateFlow(PhoneState())
    val phoneState: StateFlow<PhoneState> = _phoneState.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummaryPayload>>(emptyList())
    val sessions: StateFlow<List<SessionSummaryPayload>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
    val messages: StateFlow<List<ChatMessagePayload>> = _messages.asStateFlow()

    // sessionId と messages の atomic pair。SoundEffects.SEND 検出で session 切替時の
    // 高 id を「自分の send」と誤認しないために必要 (HistoryStore id は session 横断 autoinc)。
    private val _messagesForSession = MutableStateFlow<Pair<String?, List<ChatMessagePayload>>>(null to emptyList())
    val messagesForSession: StateFlow<Pair<String?, List<ChatMessagePayload>>> = _messagesForSession.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // HUD は最新通知だけが意味を持つので、collector 不在時に古い通知を貯めない (docs/03 §4.2)。
    private val _notifications = MutableSharedFlow<GlassNotification>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<GlassNotification> = _notifications.asSharedFlow()

    // 書き込みは mainHandler.post 経由で main thread に集約、読み取りも UI thread なので
    // @Volatile は不要。
    private var lastSeenStateSeq: Int = 0
    private var pendingInputText: Pair<Int, String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pingTimeoutRunnable = Runnable {
        log.warn("ping_timeout")
        _sessionOpen.value = false
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (bridge != null) return
        // CXR callback は binder thread。state 更新 / send を mainHandler.post で main
        // thread に集約しないと、onConnected → sendHello が apply{} 完了前に走り bridge=null
        // を観測する race がある。
        bridge = CXRServiceBridge().apply {
            setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(p0: String?, p1: String?, p2: Int) {
                    mainHandler.post {
                        log.info("cxr_connected", "pkg" to (p0 ?: ""))
                        _status.value = Status.CONNECTED
                        // Phone 側 state は distinctUntilChanged で詰まるので、hello を打って
                        // GlassRelay.refresh() に最新スナップショットを再送させる。
                        sendHello()
                    }
                }
                override fun onDisconnected() {
                    mainHandler.post {
                        log.info("cxr_disconnected")
                        _status.value = Status.DISCONNECTED
                        _sessionOpen.value = false
                        mainHandler.removeCallbacks(pingTimeoutRunnable)
                    }
                }
                override fun onConnecting(p0: String?, p1: String?, p2: Int) {
                    mainHandler.post {
                        log.info("cxr_connecting", "pkg" to (p0 ?: ""))
                        _status.value = Status.CONNECTING
                    }
                }
                override fun onARTCStatus(p0: Float, p1: Boolean) {}
                override fun onRokidAccountChanged(p0: String?) {}
            })
            subscribe(CHANNEL_FROM_PHONE, object : CXRServiceBridge.MsgCallback {
                override fun onReceive(name: String?, args: Caps?, bytes: ByteArray?) {
                    // CXR SDK は payload を `args` (Caps) で渡し `bytes` は常に null。
                    // `bytes ?: return` で受けると全 event を silent drop する (docs/03 §2.5.1)。
                    val caps = args ?: return
                    mainHandler.post { dispatchCaps(caps) }
                }
            })
        }
    }

    private fun dispatchCaps(caps: Caps) {
        val event = capsFactory.decodeFromCaps(caps)
        if (event == null) {
            log.warn("glass_drop_unknown_payload", "caps_size" to caps.size())
            return
        }
        handleWireEvent(event)
    }

    /** Wire event 1 件を state に反映する純関数。JVM 単体テストはここを直接呼ぶ。 */
    internal fun handleWireEvent(event: WireEvent) {
        when (event) {
            is SessionOpen -> {
                // Phone 再起動を吸収: 古いプロセスの seq を引きずらないよう atomicity state を reset。
                lastSeenStateSeq = 0
                pendingInputText = null
                _sessionOpen.value = true
                armPingTimeout()
            }
            is Ping -> {
                _sessionOpen.value = true
                armPingTimeout()
            }
            is SessionClose -> {
                _sessionOpen.value = false
                mainHandler.removeCallbacks(pingTimeoutRunnable)
            }
            is CurrentState -> handleCurrentState(event)
            is InputTextOnly -> handleInputTextOnly(event)
            is SessionList -> _sessions.value = event.sessions
            is CurrentSessionEvent -> _currentSessionId.value = event.id
            is MessagesEvent -> {
                _messages.value = event.messages
                _messagesForSession.value = event.sessionId to event.messages
            }
            is NotificationEvent -> emitNotification(event)
            is ErrorEvent -> _lastError.value = event.message
            // Glass → Phone 系。受信経路では現れないが exhaustive when のため列挙。
            is Hello, is SelectSession, is com.example.claudemobilehud.protocol.GestureEvent,
            is ListeningCancel, is PermissionVerdictEvent -> Unit
        }
    }

    private fun handleCurrentState(event: CurrentState) {
        if (event.seq <= lastSeenStateSeq) {
            log.debug(
                "current_state_stale_drop",
                "seq" to event.seq,
                "last_seen" to lastSeenStateSeq,
            )
            return
        }
        val effectiveInputText = pendingInputText?.let { (parentSeq, text) ->
            if (parentSeq == event.seq) text else null
        } ?: event.inputText
        _phoneState.value = PhoneState(
            mode = event.mode,
            pendingPermission = event.pendingPermission,
            transcriptState = event.transcriptState,
            transcriptText = event.transcriptText,
            inputText = effectiveInputText,
            micSource = event.micSource,
        )
        lastSeenStateSeq = event.seq
        // pending.parentSeq > 新 seq だけ「親未着の先行 InputTextOnly」として保持、それ以外は破棄。
        // 旧 `pending.first == event.seq` 比較だと parentSeq != seq の経路で ghost が残った。
        pendingInputText = pendingInputText?.takeIf { it.first > event.seq }
        StructuredLog.glassStateSwap(
            seq = event.seq,
            mode = event.mode,
            pendingRequestId = event.pendingPermission?.requestId,
            transcriptState = event.transcriptState,
            inputLen = effectiveInputText.length,
            micSource = event.micSource,
        )
    }

    private fun handleInputTextOnly(event: InputTextOnly) {
        when {
            event.parentSeq < lastSeenStateSeq -> {
                log.debug(
                    "input_text_stale_drop",
                    "parent_seq" to event.parentSeq,
                    "last_seen" to lastSeenStateSeq,
                )
            }
            event.parentSeq > lastSeenStateSeq -> {
                // 親 CurrentState 未着で先行到達。stash して後続 CurrentState で適用 (順序乱れ吸収)。
                pendingInputText = event.parentSeq to event.inputText
            }
            else -> {
                // AC-09 verifier は `glass_state_swap` event だけを invariant 対象にするので、
                // input_text のみの差し替えでも独自 event 名にせず同 event 名で出す。
                val next = _phoneState.value.copy(inputText = event.inputText)
                _phoneState.value = next
                StructuredLog.glassStateSwap(
                    seq = event.parentSeq,
                    mode = next.mode,
                    pendingRequestId = next.pendingPermission?.requestId,
                    transcriptState = next.transcriptState,
                    inputLen = next.inputText.length,
                    micSource = next.micSource,
                )
            }
        }
    }

    private fun emitNotification(event: NotificationEvent) {
        val notif = GlassNotification(
            kind = event.kind,
            text = event.text,
            sessionId = event.sessionId,
        )
        // DROP_OLDEST 指定の tryEmit は仕様上常に true を返すので戻り値は無視。
        _notifications.tryEmit(notif)
    }

    private fun armPingTimeout() {
        mainHandler.removeCallbacks(pingTimeoutRunnable)
        mainHandler.postDelayed(pingTimeoutRunnable, PING_TIMEOUT_MS)
    }

    // --- Send 系 (Glass → Phone) ---

    fun sendHello() = sendWire(Hello(ts = nowMs()))

    fun sendGesture(which: GestureKind) =
        sendWire(com.example.claudemobilehud.protocol.GestureEvent(which = which, ts = nowMs()))

    fun sendListeningCancel() = sendWire(ListeningCancel(ts = nowMs()))

    fun sendSelectSession(id: String) = sendWire(SelectSession(id = id, ts = nowMs()))

    fun sendPermissionVerdict(requestId: String, decision: PermissionDecision) =
        sendWire(PermissionVerdictEvent(requestId = requestId, decision = decision, ts = nowMs()))

    private fun sendWire(event: WireEvent) {
        val b = bridge ?: run {
            log.warn("send_dropped_bridge_null", "type" to event::class.simpleName.orEmpty())
            return
        }
        val caps = runCatching { capsFactory.encodeToCaps(event) }
            .onFailure {
                log.warn(
                    "glass_encode_failed",
                    it,
                    "type" to event::class.simpleName.orEmpty(),
                )
            }
            .getOrNull() ?: return
        // CXRServiceBridge.sendMessage 戻り値: 0=success, -1=EINVAL, -2=EDUP, -3=EFAULT, -4=EBUSY。
        // 当面は `r != 0` を一括ログ。retry を入れるならここで分岐する。
        val r = runCatching { b.sendMessage(CHANNEL_TO_PHONE, caps) }
            .onFailure {
                log.warn(
                    "glass_send_threw",
                    it,
                    "type" to event::class.simpleName.orEmpty(),
                )
            }
            .getOrDefault(-1)
        if (r != 0) {
            log.warn(
                "glass_send_nonzero",
                "type" to event::class.simpleName.orEmpty(),
                "result" to r,
            )
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    // --- テスト用フック ---
    // process singleton state を production から reset すると壊滅的なので
    // `@VisibleForTesting(otherwise=NONE)` で production 経路を Lint で抑止する。

    /** JVM 単体テスト用。process singleton state を初期化する。 */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun resetStateForTest() {
        lastSeenStateSeq = 0
        pendingInputText = null
        _status.value = Status.DISCONNECTED
        _sessionOpen.value = false
        _phoneState.value = PhoneState()
        _sessions.value = emptyList()
        _currentSessionId.value = null
        _messages.value = emptyList()
        _lastError.value = null
        mainHandler.removeCallbacks(pingTimeoutRunnable)
    }

    /** テスト用。現在の atomicity 内部状態の snapshot。 */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun atomicityStateForTest(): Pair<Int, Pair<Int, String>?> =
        lastSeenStateSeq to pendingInputText

    // phone 側 GlassConnectionService の channel 名と一致させる必要がある (CXR-L での
    // subscribe/sendMessage の宛先 key)。
    internal const val CHANNEL_FROM_PHONE = "rk_custom_client"
    internal const val CHANNEL_TO_PHONE = "rk_custom_key"
}
