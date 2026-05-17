package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.phone.data.ChannelEvent
import com.example.claudemobilehud.phone.data.ChannelRepository
import com.example.claudemobilehud.phone.data.model.PhoneUiState
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
 * Repository を購読して CXR 経由でグラスへ wire event を流す (docs/03 §3.4.1)。
 * atomicity 射程は AD-15、refresh メカニズムは §3.4.1.1、notifications を refresh の
 * 外側で起動する理由は §3.4.1.2、sender unavailable / 送信失敗の扱いは §3.4.1.3 を参照。
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

    fun refresh() {
        log.info("glass_relay_refresh")
        refreshSignal.update { it + 1 }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    private suspend fun observeCurrentState(sender: (ByteArray) -> Unit) {
        repository.currentState.collect { cs ->
            sendWire(sender, cs)
        }
    }

    private suspend fun observeSessionList(sender: (ByteArray) -> Unit) {
        repository.uiState
            .map { state -> state.sessionListForWire() }
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
            .map { state -> state.messagesForWire() }
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
                    // HUD overlay には本文を乗せない (ユーザ要望)。kind + sessionId のみ。
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
                is ChannelEvent.Sent -> Unit
            }
        }
    }

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

}

// `phone/src/test` から見えるよう internal。GlassRelayMappingTest が PhoneUiState の
// transformation を assert する (#179)。

internal fun com.example.claudemobilehud.phone.data.model.SessionSummary.toWirePayload(): SessionSummaryPayload =
    SessionSummaryPayload(
        id = id,
        label = label,
        messageCount = messageCount,
    )

internal fun com.example.claudemobilehud.phone.data.model.ChatMessage.toWirePayload(): ChatMessagePayload =
    ChatMessagePayload(
        id = id,
        role = role,
        text = text,
        chatId = chatId,
    )

/** docs/03 §3.4.1 FR-GL-20: Glass の session 一覧は active のみ。 */
internal fun PhoneUiState.sessionListForWire(): List<SessionSummaryPayload> =
    sessions.filter { it.isActive }.map { it.toWirePayload() }

/** (currentSessionId, current messages) を 1 ペアにして distinctUntilChanged を効かせる。 */
internal fun PhoneUiState.messagesForWire(): Pair<String?, List<ChatMessagePayload>> =
    currentSessionId to messages.map { it.toWirePayload() }
