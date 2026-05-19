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
 * 会話画面の表示状態ホルダ (docs/03 §4.4)。Phone 側が mode の真実で、ここは [ConversationMode]
 * を受けて表示する。Confirming/PermissionConfirming のカーソル位置だけ Glass-local に
 * [sendChoice] / [permissionChoice] で持ち、決定したら wire で Phone に送る。
 *
 * Compose 非依存 (`onGesture` を JVM テストから直接呼べる)。
 */
class ConversationStateHolder(
    private val phoneState: StateFlow<PhoneState>,
    private val onBack: () -> Unit,
    scope: CoroutineScope,
    private val bridge: Sender = DefaultSender,
) {
    /** GlassBridge への送信 + UI 効果音を 1 つの seam にまとめる (テストで差し替え可)。 */
    interface Sender {
        fun sendGesture(which: GestureKind)
        fun sendListeningCancel()
        fun sendPermissionVerdict(requestId: String, decision: PermissionDecision)
        // Glass-local の即時 feedback 用 (Phone との round-trip 待ちでは間に合わないもの)。
        fun playSfx(kind: com.example.claudemobilehud.glass.SoundEffects.Kind)
    }

    object DefaultSender : Sender {
        override fun sendGesture(which: GestureKind) = GlassBridge.sendGesture(which)
        override fun sendListeningCancel() = GlassBridge.sendListeningCancel()
        override fun sendPermissionVerdict(requestId: String, decision: PermissionDecision) =
            GlassBridge.sendPermissionVerdict(requestId, decision)
        override fun playSfx(kind: com.example.claudemobilehud.glass.SoundEffects.Kind) =
            com.example.claudemobilehud.glass.SoundEffects.play(kind)
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

    // phoneState は 1 upstream のまま combine に渡す: `map { it.mode }` と
    // `map { it.pendingPermission }` を別 upstream にすると、PhoneState 1 emit に対し
    // (mode=新 / pending=旧) の中間 emit が 1 frame 漏れ、PermissionConfirming → Idle の
    // fallback 経路を瞬間的に通る hazard が出る (NFR-13)。
    // initialValue は snapshot から derive する (Eagerly でも collector 初値生成に 1 frame
    // ぶん遅延があり、Idle 固定だと再 mount 直後の実態と乖離する)。
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
        // requestId が変わるたび ALLOW にリセット (A → B の直接遷移にも対応)。
        scope.launch {
            phoneState
                .map { it.pendingPermission?.requestId }
                .distinctUntilChanged()
                .filter { it != null }
                .collect { permissionChoice.value = PermissionChoice.ALLOW }
        }
        // Listening 直後の Confirming だけ SEND にリセット (Permission 復帰時は preserve したい)。
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

    // docs/03 §4.4 (Rev 4 追記): SEND verdict 確定の単発イベント。Konoha overlay (§4.8.9、
    // FR-GL-82) 演出のための UI-only seam で、wire には流さない。
    private val _sendEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val sendEvent: SharedFlow<Unit> = _sendEvent.asSharedFlow()

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
            GlassGesture.Tap -> {
                // 録音開始 sound は press 時点で先行発火 (状態遷移ベースだと round-trip ぶん
                // 遅延)。Phone mic capture は 50-200ms 後に始まり、その間は無音 lead-in なので
                // sound 末尾が capture に乗っても発話冒頭はカットされない。
                bridge.playSfx(com.example.claudemobilehud.glass.SoundEffects.Kind.RECORD_START)
                bridge.sendGesture(GestureKind.TAP)
            }
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
                // Listening 中の Tap 連打で 2 件目が CONFIRMING 反映前に届くと、Phone 側
                // toggleTranscription で Idle→start に戻り録音再開する hazard あり。
                // NFR-04 (round-trip < 300ms) 下では実機で踏みにくいが、根本対策には Phone 側
                // dedup が必要 (TODO)。
                bridge.sendGesture(GestureKind.TAP)
            }
            GlassGesture.SwipeForward -> _scrollRequest.tryEmit(+SCROLL_STEP)
            GlassGesture.SwipeBack -> _scrollRequest.tryEmit(-SCROLL_STEP)
            GlassGesture.DoubleTap -> {
                // 「録音停止 + 入力消去」は専用 wire 1 件で atomic に。TAP+SWIPE_BACK の 2 連
                // 送信だと Phone 側 suspend 順次処理で中間 CONFIRMING が 1 frame 漏れる (NFR-13)。
                bridge.sendListeningCancel()
            }
        }
    }

    private fun handleConfirming(g: GlassGesture, current: SendChoice) {
        when (g) {
            GlassGesture.SwipeForward, GlassGesture.SwipeBack ->
                sendChoice.value = current.toggle()
            GlassGesture.Tap -> {
                // 決定。Phone 側で SWIPE_FORWARD/BACK 受信時に confirming=false → IDLE 推移する。
                when (current) {
                    SendChoice.SEND -> {
                        _sendEvent.tryEmit(Unit)
                        bridge.sendGesture(GestureKind.SWIPE_FORWARD)
                    }
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
