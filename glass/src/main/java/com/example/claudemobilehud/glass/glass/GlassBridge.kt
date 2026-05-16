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
 * Glass 側の唯一の CXR エンドポイント (Phase 3 §4.2)。Phone からの wire event を
 * 受信して内部 [StateFlow] に反映、Glass → Phone は send 系メソッドで送る。
 *
 * **NFR-13 atomicity (§4.2.1)**:
 *   - `lastSeenStateSeq`: 既知の CurrentState.seq の最大値。`<= lastSeen` の
 *     CurrentState は stale としてドロップ。
 *   - `pendingInputText: Pair<Int, String>?`: `InputTextOnly` が先行到着した場合に
 *     1 件だけ stash する (parent_seq, text)。後続の CurrentState で同じ seq が
 *     来たら適用、ズレた場合は無視。
 *   - `SessionOpen` を受けたら両方 reset (Phone 再起動を吸収。Phase 2 §4.5.3 B-1)。
 *
 * **ライフサイクル**:
 *   - `init(context)`: CXRServiceBridge 初期化 + Phone subscribe。冪等。
 *   - status / sessionOpen / phoneState / ... は process singleton な StateFlow。
 *
 * **テスト可能性**:
 *   - JNI/binder に触れない `internal fun handleWireEvent(event: WireEvent)` を
 *     公開し、JVM 単体テストから wire event を直接打ち込んで atomicity を検証可能。
 *   - `resetStateForTest()` で全状態を初期化できる。
 */
object GlassBridge {
    private val log = StructuredLog("channel.glass.bridge")
    private const val PING_TIMEOUT_MS = 12_000L

    // P2-D of 5a review: bridge は init() (main thread) で書かれ、send 系 / status callback
    // (binder thread) から読まれる。`@Volatile` で publish の happens-before を保証。
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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // 一過性 event。Phase 3 §4.2: replay=0, buffer=8, DROP_OLDEST。
    // collector 不在のときに古い通知が貯まらないようにする (HUD は最新だけが意味を持つ)。
    private val _notifications = MutableSharedFlow<GlassNotification>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<GlassNotification> = _notifications.asSharedFlow()

    // §4.2.1 の atomicity state。`@Volatile` を付けないのは、書き換えが必ず main
    // thread 上 (mainHandler.post 経由) に集約されているため。読み取りも UI thread
    // から行うので可視性問題は出ない。
    private var lastSeenStateSeq: Int = 0
    private var pendingInputText: Pair<Int, String>? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pingTimeoutRunnable = Runnable {
        log.warn("ping_timeout")
        _sessionOpen.value = false
    }

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (bridge != null) return
        // P2-C of 5a review: CXR callback は binder thread。state 更新 + send は全て
        // mainHandler.post で main thread に集約する (phone GlassConnectionService と同じパターン)。
        // status callback を直接走らせると `_status.value=CONNECTED` 直後の sendHello が
        // CXRServiceBridge().apply { ... } の完了前に走るレース (bridge 観測 null) を作りうる。
        bridge = CXRServiceBridge().apply {
            setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(p0: String?, p1: String?, p2: Int) {
                    mainHandler.post {
                        log.info("cxr_connected", "pkg" to (p0 ?: ""))
                        _status.value = Status.CONNECTED
                        // 自プロセス再起動時、phone 側 state は不変なので StateFlow が
                        // distinctUntilChanged で詰まり再送されない。hello を投げて
                        // phone GlassRelay.refresh() を強制発火させ最新スナップショットを得る。
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
                    // CXRServiceBridge は `bytes` を常に null で呼び、ペイロードは `args`
                    // (Caps) で渡してくる (実機確認済、POC も args 経由)。`bytes ?: return`
                    // 経路だと Phone から来る wire event が全部 silent drop される。
                    val caps = args ?: return
                    // CXR binder thread から呼ばれる。state 更新は main thread に集約。
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

    /**
     * Wire event 1 件を内部 state に反映する純関数 (JNI 非依存)。
     * JVM 単体テストはこのメソッドを直接呼ぶ。
     */
    internal fun handleWireEvent(event: WireEvent) {
        when (event) {
            is SessionOpen -> {
                // Phase 2 §4.5.3 B-1: Phone 再起動を吸収。seq 由来の stale 判定が
                // 古いプロセスの seq を引きずらないように両方 reset。
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
            is MessagesEvent -> _messages.value = event.messages
            is NotificationEvent -> emitNotification(event)
            is ErrorEvent -> _lastError.value = event.message
            // Glass → Phone 系。Glass 側受信経路では現れないが exhaustive にする。
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
        // P2-E of 5a review: seq advance のたびに pending を必ず再評価する。
        //   - 旧コード: `if (pending?.first == event.seq) pending = null`。
        //     parentSeq != event.seq の経路で pending が永遠に残り、将来 logic 拡張で
        //     ghost を誤 apply するリスクがあった。
        //   - 新コード: pending.parentSeq > 新 seq の場合のみ "まだ親 state 未着の
        //     先行 InputTextOnly" として保持。<= の場合は確実に古いので破棄。
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
                // 既に新しい CurrentState が反映済み。古い text 差分は捨てる。
                log.debug(
                    "input_text_stale_drop",
                    "parent_seq" to event.parentSeq,
                    "last_seen" to lastSeenStateSeq,
                )
            }
            event.parentSeq > lastSeenStateSeq -> {
                // 親 CurrentState 未着で先行到達。1 件だけ stash しておき、後続
                // CurrentState 受信時に同 seq なら適用する (パイプの順序乱れ吸収)。
                pendingInputText = event.parentSeq to event.inputText
            }
            else -> {
                // P2-F of 5a review: 設計書 §4.2.1 (line 1343-1349) 通り、input_text のみ
                // 差し替えた場合も `glass_state_swap` event でログを出す。AC-09 verifier は
                // `glass_state_swap` だけを invariant 対象にしているため、独自 event 名
                // (`glass_input_swap`) を使うと検証から除外され、mode/input_text の整合性が
                // 静かに無視される。
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
        // P3-B of 5a review: `MutableSharedFlow(replay=0, buffer=8, DROP_OLDEST)` の
        // `tryEmit` は kotlinx.coroutines 仕様で **常に true** を返す (DROP_OLDEST が
        // 必ず slot を確保する)。旧来の `if (!tryEmit) warn` は dead code だった。
        // 戻り値を捨て、契約に依存することを明示する。
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
        // P3-C of 5a review: CXRServiceBridge.sendMessage の戻り値は SDK constants:
        //   0 = success, -1 = EINVAL, -2 = EDUP, -3 = EFAULT, -4 = EBUSY
        // POC 同様 `r != 0` を非 success として一括ログするが、将来 retry ロジックを
        // 入れるならここで分岐するための注記を残す。
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
    // P3-A of 5a review: `@VisibleForTesting(otherwise=NONE)` で production code から
    // 誤って呼ばれた場合に AndroidX Lint が warning を出す (process singleton state を
    // production 経路で reset すると壊滅的)。

    /** JVM 単体テスト用。process singleton の state を全部初期化する。 */
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

    // POC との後方互換のため。phone 側 GlassConnectionService の channel 名と一致。
    internal const val CHANNEL_FROM_PHONE = "rk_custom_client"
    internal const val CHANNEL_TO_PHONE = "rk_custom_key"
}
