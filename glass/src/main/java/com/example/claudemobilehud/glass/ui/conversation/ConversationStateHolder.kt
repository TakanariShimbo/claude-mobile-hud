package com.example.claudemobilehud.glass.ui.conversation

import androidx.compose.runtime.Stable
import com.example.claudemobilehud.glass.gesture.GlassGesture
import com.example.claudemobilehud.glass.glass.GlassBridge
import com.example.claudemobilehud.glass.glass.PhoneState
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.GestureKind
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.PermissionDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 会話画面の表示状態ホルダ。Phase 3 §4.4 (Rev 1 構造を維持しつつ `PhoneState` を 1 つの
 * 上流とする)。
 *
 * **モード決定 (LISTENING / PERMISSION_CONFIRMING / CONFIRMING / IDLE) は phone 側が真実**
 * で、このクラスはその [ConversationMode] を wire で受信してそのまま表示する。glass 固有の
 * 関心事は「Confirming / PermissionConfirming 内でどちらの選択肢にカーソルがあるか」だけで、
 * これは round-trip させたくないので [sendChoice] / [permissionChoice] として glass-local に
 * 持つ (= ホバー / カーソル位置のみ glass で完結)。
 *
 * ジェスチャは現在モードに応じて分岐し、必要な「決定」だけを wire で phone に送る。
 *
 * 4-5b: POC は `GlassBridge.currentMode` / `pendingPermission` という 2 つの独立 StateFlow を
 * 受けていたが、新 wire (`CurrentState` 単発 event) で原子的に合わさる `PhoneState` に集約。
 *
 * Compose 非依存。Composable から remember で 1 インスタンス保持し:
 *   - [state] を collectAsStateWithLifecycle で観測
 *   - [scrollRequest] を LaunchedEffect で消費して LazyListState を動かす
 *   - GestureBus.events を [onGesture] にそのまま流す
 */
class ConversationStateHolder(
    private val phoneState: StateFlow<PhoneState>,
    private val onBack: () -> Unit,
    scope: CoroutineScope,
    private val bridge: Sender = DefaultSender,
) {
    /** GlassBridge への送信を 1 個の seam にまとめる (テストで差し替え可)。 */
    interface Sender {
        fun sendGesture(which: GestureKind)
        fun sendListeningCancel()
        fun sendPermissionVerdict(requestId: String, decision: PermissionDecision)
    }

    object DefaultSender : Sender {
        override fun sendGesture(which: GestureKind) = GlassBridge.sendGesture(which)
        override fun sendListeningCancel() = GlassBridge.sendListeningCancel()
        override fun sendPermissionVerdict(requestId: String, decision: PermissionDecision) =
            GlassBridge.sendPermissionVerdict(requestId, decision)
    }

    @Stable
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Confirming(val choice: SendChoice) : State
        data class PermissionConfirming(
            val choice: PermissionChoice,
            val pending: PendingPermissionPayload,
        ) : State
    }

    enum class SendChoice { SEND, CANCEL }
    enum class PermissionChoice { ALLOW, DENY }

    // --- glass-local cursor (ホバー位置だけ。決定したら wire で phone へ送る) ---
    private val sendChoice = MutableStateFlow(SendChoice.SEND)
    private val permissionChoice = MutableStateFlow(PermissionChoice.ALLOW)

    // P1-A of 5b review: NFR-13 atomicity を Glass UI でも維持するため、`phoneState` を
    // 1 上流のまま combine に渡す。旧コードは `phoneState.map { it.mode }` と
    // `phoneState.map { it.pendingPermission }` を別 upstream として `combine` していたが、
    // `combine` は派生 flow ごとに独立 collect するため、PhoneState 1 emit に対して
    // 中間 emit (mode=新 / pending=旧) が 1 frame だけ漏れ、`State.PermissionConfirming
    // → fallback Idle` の経路が瞬間的に通る hazard があった。PhoneState を 1 flow にすれば
    // distinctUntilChanged で常に原子値で配信される。
    //
    // P2-D of 5b review: initialValue は `phoneState.value` から derive する。
    // SharingStarted.Eagerly でも collector が初値を作るまでに 1 frame の遅延があり、
    // 旧来 `State.Idle` 固定だと再 mount 直後に Listening などの実態と乖離した値が
    // 観測されていた。
    val state: StateFlow<State> = combine(
        phoneState,
        sendChoice,
        permissionChoice,
    ) { ps, sc, pc -> compute(ps, sc, pc) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = compute(phoneState.value, sendChoice.value, permissionChoice.value),
        )

    private fun compute(ps: PhoneState, sc: SendChoice, pc: PermissionChoice): State =
        when (ps.mode) {
            ConversationMode.LISTENING -> State.Listening
            ConversationMode.PERMISSION_CONFIRMING -> ps.pendingPermission?.let {
                State.PermissionConfirming(pc, it)
            } ?: State.Idle
            ConversationMode.CONFIRMING -> State.Confirming(sc)
            ConversationMode.IDLE -> State.Idle
        }

    init {
        // permission の identity (requestId) が変わるたび permissionChoice を ALLOW にリセット。
        // null → non-null だけでなく A → B の直接遷移にも対応する。
        scope.launch {
            phoneState
                .map { it.pendingPermission?.requestId }
                .distinctUntilChanged()
                .filter { it != null }
                .collect { permissionChoice.value = PermissionChoice.ALLOW }
        }
        // Listening → Confirming に遷移したら sendChoice を SEND にリセット。
        // (PermissionConfirming → Confirming のような割り込み復帰では preserve したいので、
        // 「Listening 直後の Confirming」だけに絞っている)
        scope.launch {
            var prev: ConversationMode? = null
            phoneState.map { it.mode }.distinctUntilChanged().collect { cur ->
                if (prev == ConversationMode.LISTENING && cur == ConversationMode.CONFIRMING) {
                    sendChoice.value = SendChoice.SEND
                }
                prev = cur
            }
        }
    }

    // --- scroll は単発イベントなので SharedFlow ---
    private val _scrollRequest = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val scrollRequest: SharedFlow<Int> = _scrollRequest.asSharedFlow()

    // --- gesture dispatch (State の subtype に直接分岐) ---
    fun onGesture(g: GlassGesture) {
        when (val s = state.value) {
            State.Idle -> handleIdle(g)
            State.Listening -> handleListening(g)
            is State.Confirming -> handleConfirming(g, s.choice)
            is State.PermissionConfirming -> handlePermissionConfirming(g, s.choice, s.pending)
        }
    }

    private fun handleIdle(g: GlassGesture) {
        when (g) {
            GlassGesture.Tap -> bridge.sendGesture(GestureKind.TAP)
            GlassGesture.SwipeForward -> _scrollRequest.tryEmit(+SCROLL_STEP)
            GlassGesture.SwipeBack -> _scrollRequest.tryEmit(-SCROLL_STEP)
            GlassGesture.DoubleTap -> {
                bridge.sendGesture(GestureKind.DOUBLE_TAP)
                onBack()
            }
        }
    }

    private fun handleListening(g: GlassGesture) {
        when (g) {
            GlassGesture.Tap -> {
                // phone 側で TAP を受けると「Listening → stop + confirming=true」となり、
                // 続けて mode push (CONFIRMING) が wire で帰ってくる。glass は次の mode 更新で
                // Confirming UI に切替わる (round-trip 1 回ぶんの遅延あり)。
                //
                // P2-C of 5b review: Listening 中の Tap 連打で 2 件目が「CONFIRMING 反映前に」
                // また TAP 送信されると、Phone 側 toggleTranscription で「Idle→start」となり
                // 録音再開してしまう既知 hazard。NFR-04 (round-trip < 300ms) が満たされてれば
                // 実機操作で踏みにくいが、根本対策には phone 側に dedup を持たせる必要あり。
                // 5c (もしくは Phase 4 review 後) で扱う TODO。
                bridge.sendGesture(GestureKind.TAP)
            }
            GlassGesture.SwipeForward -> _scrollRequest.tryEmit(+SCROLL_STEP)
            GlassGesture.SwipeBack -> _scrollRequest.tryEmit(-SCROLL_STEP)
            GlassGesture.DoubleTap -> {
                // P1-C of 5b review: 「録音停止 + 入力消去」は専用 wire `ListeningCancel` で
                // 1 件送信する。旧コードは TAP + SWIPE_BACK の 2 連送信で、Phone GlassEvent
                // Dispatcher が suspend 順次処理するために中間 CurrentState(CONFIRMING) が
                // 1 frame 漏れていた (NFR-13 違反相当)。設計書 §4.2 + protocol.ListeningCancel
                // 既存 wire を活用する。
                bridge.sendListeningCancel()
            }
        }
    }

    private fun handleConfirming(g: GlassGesture, current: SendChoice) {
        when (g) {
            GlassGesture.SwipeForward, GlassGesture.SwipeBack ->
                sendChoice.value = current.toggle()
            GlassGesture.Tap -> {
                // 決定。phone 側で SWIPE_FORWARD/BACK を受けると confirming=false に倒れ、
                // 続く mode push (IDLE) で UI が抜ける。
                when (current) {
                    SendChoice.SEND -> bridge.sendGesture(GestureKind.SWIPE_FORWARD)
                    SendChoice.CANCEL -> bridge.sendGesture(GestureKind.SWIPE_BACK)
                }
            }
            GlassGesture.DoubleTap -> {
                // ダブルタップ = 取消 (送信しない)。会話画面には残る。
                bridge.sendGesture(GestureKind.SWIPE_BACK)
            }
        }
    }

    private fun handlePermissionConfirming(
        g: GlassGesture,
        current: PermissionChoice,
        pending: PendingPermissionPayload,
    ) {
        when (g) {
            GlassGesture.SwipeForward, GlassGesture.SwipeBack ->
                permissionChoice.value = current.toggle()
            GlassGesture.Tap ->
                bridge.sendPermissionVerdict(pending.requestId, current.toWire())
            GlassGesture.DoubleTap ->
                // ダブルタップ = 拒否 (安全側 / FR-GL-62)。
                bridge.sendPermissionVerdict(pending.requestId, PermissionChoice.DENY.toWire())
        }
    }

    private fun SendChoice.toggle(): SendChoice =
        if (this == SendChoice.SEND) SendChoice.CANCEL else SendChoice.SEND

    private fun PermissionChoice.toggle(): PermissionChoice =
        if (this == PermissionChoice.ALLOW) PermissionChoice.DENY else PermissionChoice.ALLOW

    private fun PermissionChoice.toWire(): PermissionDecision = when (this) {
        PermissionChoice.ALLOW -> PermissionDecision.ALLOW
        PermissionChoice.DENY -> PermissionDecision.DENY
    }

    private companion object {
        // 1 swipe で送る行数。Compose 側でこの値に行高 (sp) を掛けて animateScrollBy する。
        const val SCROLL_STEP = 3
    }
}
