package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.phone.data.ChannelEvent
import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.phone.service.GlassConnectionService
import com.example.claudemobilehud.protocol.ChatMessagePayload
import com.example.claudemobilehud.protocol.CurrentSessionEvent
import com.example.claudemobilehud.protocol.MessagesEvent
import com.example.claudemobilehud.protocol.NotificationEvent
import com.example.claudemobilehud.protocol.NotificationKind
import com.example.claudemobilehud.protocol.SessionList
import com.example.claudemobilehud.protocol.SessionSummaryPayload
import com.example.claudemobilehud.protocol.WireEvent
import com.example.claudemobilehud.protocol.codec.CapsCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Repository を購読して CXR 経由でグラスへ wire event を流す。Phase 3 §3.4.1。
 *
 * **NFR-13 atomicity 射程** (AD-15):
 *   - `currentState` (mode + pending/transcript/input/micSource) は 1 wire で原子的に push
 *   - sessions / current_session / messages / notifications は eventual consistency
 *
 * **refresh()**: Glass hello を受けたとき呼ぶ。`refreshSignal` を bump し observer 群を
 * 再起動 → 各 StateFlow の現在値が distinctUntilChanged を貫通して再 emit される
 * (Glass プロセス再起動など phone 側 state 不変ケースの初期同期に必要)。
 *
 * **sender unavailable**: `GlassConnectionService.sender` が null の間は何も流さない。
 * Glass 接続が確立して non-null になると collectLatest が observer を起動する。
 */
class GlassRelay(
    private val repository: ChannelRepository,
    private val codec: CapsCodec,
) {
    private val log = StructuredLog("channel.glass.relay")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null
    private val refreshSignal = MutableStateFlow(0)

    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            GlassConnectionService.sender.collectLatest { sender ->
                if (sender == null) return@collectLatest
                log.info("glass_relay_sender_attached")
                // 通知 (一過性 event) は refresh で取り直しできないため refreshSignal の
                // re-entrant cancel scope の外側で 1 度だけ起動。
                launch { observeNotifications(sender) }
                refreshSignal.collectLatest {
                    coroutineScope {
                        launch { observeCurrentState(sender) }
                        launch { observeSessionList(sender) }
                        launch { observeCurrentSession(sender) }
                        launch { observeMessages(sender) }
                    }
                }
            }
        }
    }

    /** Glass hello で trigger。observer 群を再起動して現在値を再送。 */
    fun refresh() {
        log.info("glass_relay_refresh")
        // P2-7: 並行 refresh 呼び出しで read-modify-write が衝突しないように update を使う。
        refreshSignal.update { it + 1 }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    // --- 個別 observer ---

    private suspend fun observeCurrentState(sender: (ByteArray) -> Unit) {
        // currentState は Repository が seq を採番して 1 emit に 1 値を保証している (P1-7)。
        repository.currentState.collect { cs ->
            sendWire(sender, cs)
        }
    }

    private suspend fun observeSessionList(sender: (ByteArray) -> Unit) {
        // FR-GL-20: Glass の session 一覧は **アクティブな session のみ** 表示する。
        // Phone 側 SessionDrawer (`ui.sessions`) は履歴アクセスも兼ねるため
        // inactive を含めて出すが、Glass は「いま操作できる session」だけを示すので
        // ここで filter する。POC でも Glass 側は active のみだった。
        // 注意: `current_session` wire (observeCurrentSession) は filter しないので、
        // current が inactive 化したケースでは「list に居ない session が current」に
        // なり得る。Glass UI 側は indexOfFirst で `-1` ガード済みなのでクラッシュは
        // しないが、§3.4.1 の補注も参照。
        repository.uiState
            .map { state ->
                state.sessions.filter { it.isActive }.map { it.toWirePayload() }
            }
            .distinctUntilChanged()
            .collect { list ->
                sendWire(sender, SessionList(sessions = list, ts = System.currentTimeMillis()))
            }
    }

    private suspend fun observeCurrentSession(sender: (ByteArray) -> Unit) {
        repository.uiState
            .map { it.currentSessionId }
            .distinctUntilChanged()
            .collect { id ->
                sendWire(sender, CurrentSessionEvent(id = id, ts = System.currentTimeMillis()))
            }
    }

    private suspend fun observeMessages(sender: (ByteArray) -> Unit) {
        repository.uiState
            .map { state -> state.currentSessionId to state.messages.map { it.toWirePayload() } }
            .distinctUntilChanged()
            .collect { (sessionId, messages) ->
                sendWire(
                    sender,
                    MessagesEvent(
                        sessionId = sessionId,
                        messages = messages,
                        ts = System.currentTimeMillis(),
                    ),
                )
            }
    }

    private suspend fun observeNotifications(sender: (ByteArray) -> Unit) {
        repository.events.collect { event ->
            when (event) {
                is ChannelEvent.Reply -> {
                    // 本文は HUD overlay に乗せない (POC ユーザ要望)。kind + sessionId のみ。
                    sendWire(
                        sender,
                        NotificationEvent(
                            kind = NotificationKind.REPLY,
                            text = "",
                            sessionId = event.sessionId,
                            ts = System.currentTimeMillis(),
                        ),
                    )
                }
                is ChannelEvent.PermissionRequested -> {
                    val p = event.pending
                    sendWire(
                        sender,
                        NotificationEvent(
                            kind = NotificationKind.PERMISSION,
                            text = "${p.toolName}: ${p.description}",
                            sessionId = p.sessionId,
                            ts = System.currentTimeMillis(),
                        ),
                    )
                }
                is ChannelEvent.Sent -> Unit // glass HUD 表示不要
            }
        }
    }

    /**
     * P2-8: encode 失敗時は警告のみ (UI に出すレベルではなく開発時 bug)。`ts` を含めて
     * logcat で重複検出しやすくする。send 失敗は CXR 一時障害なのでログ詳細を増やす。
     */
    private inline fun sendWire(sender: (ByteArray) -> Unit, event: WireEvent) {
        val payload = runCatching { codec.encode(event) }
            .onFailure {
                log.warn(
                    "glass_encode_failed",
                    it,
                    "event" to event::class.simpleName.orEmpty(),
                    "ts" to event.ts,
                )
            }
            .getOrNull() ?: return
        runCatching { sender(payload) }
            .onFailure {
                log.warn(
                    "glass_send_failed",
                    it,
                    "event" to event::class.simpleName.orEmpty(),
                    "bytes" to payload.size,
                )
            }
    }

    // --- payload mapper (protocol.MessageRole は phone.data.model から直接再 export 済) ---

    private fun com.example.claudemobilehud.phone.data.model.SessionSummary.toWirePayload(): SessionSummaryPayload =
        SessionSummaryPayload(
            id = id,
            label = label,
            messageCount = messageCount,
        )

    private fun com.example.claudemobilehud.phone.data.model.ChatMessage.toWirePayload(): ChatMessagePayload =
        ChatMessagePayload(
            id = id,
            role = role,
            text = text,
            chatId = chatId,
        )
}
