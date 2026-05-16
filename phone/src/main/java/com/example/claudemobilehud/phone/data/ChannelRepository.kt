package com.example.claudemobilehud.phone.data

import android.content.Context
import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.error.TransientError
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.PendingPermission
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.log.StructuredLog
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.CurrentState
import com.example.claudemobilehud.protocol.MicSource
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.TranscriptState
import com.example.claudemobilehud.protocol.error.SharedWireError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/**
 * Phase 3 §3.2.1 の data 層 facade。
 *
 * 4a (今コミット) の範囲:
 *   - SettingsStore / HistoryStore / SessionStore / ConnectionController / ChannelClient を組み立て
 *   - SSE event を内部状態へ反映
 *   - `currentState: StateFlow<CurrentState>` を combine + collector-level seq で生成 (NFR-13)
 *   - send / respondPermission / selectSession / updateInputText / clearInput / saveSettings /
 *     reconnect / flushHistory を公開
 *
 * 4b 以降で組み込む (現状 stub):
 *   - transcription (TranscriptionClient): transcriptState は常に IDLE
 *   - image staging (ImageProcessor): attachImage は path をそのまま保持
 *   - confirming gesture: confirmingBySession は常に空
 *   - mic source: GLASS 固定 (BT SCO 失敗時の PHONE_FALLBACK 切替は 4b)
 */
class ChannelRepository(
    private val applicationContext: Context,
    historyFilesDir: File = applicationContext.filesDir,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = StructuredLog("mhud.repo")
    private val settingsStore = SettingsStore(applicationContext)
    private val historyStore = HistoryStore(historyFilesDir)
    private val sessionStore = SessionStore(historyStore)
    private val connection = ConnectionController()

    // 公開 flow
    val settings: StateFlow<Settings>
    val connectivity: StateFlow<ConnectivityState> get() = connection.status
    val uiState: StateFlow<PhoneUiState>

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> get() = _inputText.asStateFlow()

    private val _attachedImage = MutableStateFlow<ImageAttachment?>(null)
    val attachedImage: StateFlow<ImageAttachment?> get() = _attachedImage.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingPermissions: StateFlow<List<PendingPermission>> get() = _pendingPermissions.asStateFlow()

    private val _events = MutableSharedFlow<ChannelEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ChannelEvent> = _events.asSharedFlow()

    private val _errors = MutableSharedFlow<TransientError>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<TransientError> = _errors.asSharedFlow()

    /**
     * `currentState` (= Glass 向け wire push の正本)。collector 内で seq を 1 ずつ採番。
     * NFR-13 / AD-03。Phase 3 §3.2.1.2。
     */
    private val seqCounter = AtomicInteger(0)
    private val _currentState = MutableStateFlow(initialCurrentState())
    val currentState: StateFlow<CurrentState> = _currentState.asStateFlow()

    private val settingsFlow: StateFlow<Settings>

    init {
        settingsFlow = MutableStateFlow(Settings())
        settings = settingsFlow

        scope.launch {
            // Settings の hot stream を内部に流す
            settingsStore.settings.collect { newSettings ->
                (settingsFlow as MutableStateFlow).value = newSettings
                connection.update(newSettings)
            }
        }

        // SSE event の処理
        connection.events.onEach(::onSseEvent).launchIn(scope)

        // uiState の合成
        uiState = combine(
            sessionStore.snapshot,
            _pendingPermissions,
            _inputText,
            _attachedImage,
            connectivity,
        ) { sessionSnap, pending, input, image, conn ->
            PhoneUiState(
                sessions = sessionSnap.sessions,
                currentSessionId = sessionSnap.currentSessionId,
                messages = sessionSnap.messages,
                pendingPermissions = pending,
                inputText = input,
                attachedImage = image,
                mode = deriveMode(sessionSnap.currentSessionId, pending, TranscriptState.IDLE),
                transcriptText = "",
                connectivity = conn,
            )
        }.distinctUntilChanged().let { flow ->
            val initial = PhoneUiState()
            val state = MutableStateFlow(initial)
            flow.onEach { state.value = it }.launchIn(scope)
            state.asStateFlow()
        }

        // currentState の合成 (§3.2.1.2)
        scope.launch {
            combine(
                sessionStore.snapshot.map { it.currentSessionId },
                _pendingPermissions,
                _inputText,
            ) { currentSession, pending, inputText ->
                CurrentStateDraft(
                    mode = deriveMode(currentSession, pending, TranscriptState.IDLE),
                    pendingPermission = pending.firstOrNull { it.sessionId == currentSession }
                        ?.toWirePayload(),
                    transcriptState = TranscriptState.IDLE,
                    transcriptText = "",
                    inputText = inputText,
                    micSource = MicSource.GLASS,
                )
            }
                .distinctUntilChanged()
                .collect { draft ->
                    val newSeq = seqCounter.incrementAndGet()
                    val payload = draft.toPayload(seq = newSeq)
                    _currentState.value = payload
                    StructuredLog.phoneStateEmit(payload)
                }
        }
    }

    suspend fun initialize() {
        sessionStore.restoreFromHistory()
        val initial = settingsStore.snapshot()
        sessionStore.restoreCurrentSessionId(initial.lastCurrentSessionId)
    }

    // --- 公開 action ---

    suspend fun saveSettings(s: Settings) {
        settingsStore.save(s)
    }

    fun reconnect() = connection.reconnect()

    suspend fun selectSession(sessionId: String) {
        sessionStore.selectSession(sessionId)
        settingsStore.saveLastCurrentSessionId(sessionId)
    }

    suspend fun deleteSession(sessionId: String) = sessionStore.deleteSession(sessionId)

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun clearInput() {
        _inputText.value = ""
        _attachedImage.value = null
    }

    fun attachImage(image: ImageAttachment) {
        _attachedImage.value = image
    }

    fun clearAttachedImage() {
        _attachedImage.value = null
    }

    /**
     * 現在 session 宛にテキスト送信。chat_id は Hub mint。失敗時は events / errors に emit。
     * 4b で image (base64) 添付に対応 (現状は path のみ持つ Attachment を ignore)。
     */
    suspend fun send(text: String) {
        if (text.isBlank() && _attachedImage.value == null) return
        val sessionId = sessionStore.snapshot.value.currentSessionId
        val image = _attachedImage.value
        // appendOutgoing で UI に即時反映 (UNKNOWN_SESSION_ID 経由になる場合あり)
        val pending = sessionStore.appendOutgoing(
            sessionId = sessionId,
            text = text,
            chatId = null,
            image = image,
        )
        clearInput()
        val client = connection.client.value
        if (client == null) {
            _errors.tryEmit(
                TransientError.Shared(SharedWireError.Connection.NotConfigured),
            )
            return
        }
        val result = client.send(
            text = text,
            sessionId = sessionId,
            image = image,
            imageBase64 = null, // 4b で ImageProcessor 結果を渡す
        )
        result
            .onSuccess { resp ->
                log.info(
                    "send_ok",
                    "chat_id" to resp.chatId,
                    "session_id" to (resp.sessionId ?: ""),
                )
                // Pending メッセージに chat_id / session_id を付け直す処理は 4b で
                // (今は最小機能として ack なし)
                _events.tryEmit(ChannelEvent.Sent(resp.chatId, pending.id))
            }
            .onFailure { err -> emitErrorFromThrowable(err) }
    }

    suspend fun respondPermission(requestId: String, behavior: PermissionDecision) {
        val client = connection.client.value
        if (client == null) {
            _errors.tryEmit(TransientError.Shared(SharedWireError.Connection.NotConfigured))
            return
        }
        client.sendPermissionVerdict(requestId, behavior)
            .onSuccess {
                _pendingPermissions.update { current ->
                    current.filterNot { it.requestId == requestId }.toList()
                }
            }
            .onFailure { err -> emitErrorFromThrowable(err) }
    }

    suspend fun flushHistory() = sessionStore.flush()

    // --- 内部: SSE event ハンドラ ---

    private suspend fun onSseEvent(event: SseEvent) {
        sessionStore.applySseEvent(event)
        when (event) {
            is SseEvent.PermissionSnapshot -> {
                val authority = event.requestIds.toSet()
                _pendingPermissions.update { current ->
                    current.filter { it.requestId in authority }.toList()
                }
            }
            is SseEvent.Permission -> {
                _pendingPermissions.update { current ->
                    if (current.any { it.requestId == event.requestId }) current
                    else (current + PendingPermission(
                        requestId = event.requestId,
                        sessionId = event.sessionId,
                        toolName = event.toolName,
                        description = event.description,
                        inputPreview = event.inputPreview,
                        createdAtMs = System.currentTimeMillis(),
                    )).toList()
                }
                _events.tryEmit(ChannelEvent.PermissionRequested(event.requestId))
            }
            is SseEvent.PermissionAbort -> {
                _pendingPermissions.update { current ->
                    val filtered = current.filterNot { it.requestId == event.requestId }
                    if (filtered.size == current.size) current else filtered.toList()
                }
                _errors.tryEmit(
                    TransientError.Shared(SharedWireError.Permission.Aborted(event.requestId)),
                )
            }
            is SseEvent.Reply -> {
                _events.tryEmit(ChannelEvent.Reply(event.chatId, event.sessionId))
            }
            else -> Unit
        }
    }

    private fun emitErrorFromThrowable(err: Throwable) {
        val wire = (err as? WireErrorException)?.wireError
        val transient = when (wire) {
            is SharedWireError -> TransientError.Shared(wire)
            is PhoneWireError -> TransientError.Phone(wire)
            else -> TransientError.Shared(
                SharedWireError.Connection.ConnectFailed(err.message),
            )
        }
        _errors.tryEmit(transient)
    }

    private fun deriveMode(
        currentSessionId: String?,
        pending: List<PendingPermission>,
        transcriptState: TranscriptState,
    ): ConversationMode {
        val hasPendingForCurrent = pending.any { it.sessionId == currentSessionId }
        return when {
            hasPendingForCurrent -> ConversationMode.PERMISSION_CONFIRMING
            transcriptState == TranscriptState.LISTENING -> ConversationMode.LISTENING
            else -> ConversationMode.IDLE
        }
    }

    private fun initialCurrentState(): CurrentState = CurrentState(
        seq = 0,
        mode = ConversationMode.IDLE,
        pendingPermission = null,
        transcriptState = TranscriptState.IDLE,
        transcriptText = "",
        inputText = "",
        micSource = MicSource.GLASS,
        ts = System.currentTimeMillis(),
    )

    private data class CurrentStateDraft(
        val mode: ConversationMode,
        val pendingPermission: com.example.claudemobilehud.protocol.PendingPermissionPayload?,
        val transcriptState: TranscriptState,
        val transcriptText: String,
        val inputText: String,
        val micSource: MicSource,
    ) {
        fun toPayload(seq: Int): CurrentState = CurrentState(
            seq = seq,
            mode = mode,
            pendingPermission = pendingPermission,
            transcriptState = transcriptState,
            transcriptText = transcriptText,
            inputText = inputText,
            micSource = micSource,
            ts = System.currentTimeMillis(),
        )
    }
}

/** 一過性イベント (UI が SnackBar / 通知トリガに使う)。 */
sealed class ChannelEvent {
    data class Reply(val chatId: String, val sessionId: String?) : ChannelEvent()
    data class PermissionRequested(val requestId: String) : ChannelEvent()
    data class Sent(val chatId: String, val localMessageId: Long) : ChannelEvent()
}

/** MutableStateFlow の atomic update helper (kotlinx.coroutines にあるが import 簡素化)。 */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    while (true) {
        val current = value
        val updated = transform(current)
        if (compareAndSet(current, updated)) return
    }
}
