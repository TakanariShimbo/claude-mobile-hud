package com.example.claudemobilehud.phone.data

import android.content.Context
import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.error.TransientError
import com.example.claudemobilehud.phone.data.model.ChatMessage
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
import com.example.claudemobilehud.protocol.PendingPermissionPayload
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 §3.2.1 の data 層 facade。
 *
 * **Atomicity 戦略 (NFR-13)**:
 * 単一の `currentStateDraft: StateFlow<CurrentStateDraft>` を内部 source of truth として持つ。
 *   - `currentState` (Glass wire 用) はこれに collector-level seq を被せる
 *   - `uiState` (Phone UI 用) はこれに sessions/messages/attachedImage/connectivity を merge する
 * これにより mode の Phone UI 観測と Glass wire push が同じ draft 値から派生するため、
 * 1 描画フレーム内で乖離しない (P1-7 / 設計書 §4.5.1)。
 *
 * 4b 以降で組み込む (現状 stub):
 *   - transcription (TranscriptionClient): transcriptState は常に IDLE
 *   - image staging (ImageProcessor): attachImage は path のみ保持
 *   - confirming gesture: 4b で mode に追加
 *   - mic source: GLASS 固定 (BT SCO 失敗時の PHONE_FALLBACK 切替は 4b)
 */
class ChannelRepository(
    private val applicationContext: Context,
    historyFilesDir: File = applicationContext.filesDir,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val log = StructuredLog("channel.repo")
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

    /**
     * 一過性 channel event。replay=0 を意図的に維持している:
     *   - ChannelService の collector は Application.onCreate で attach されるので、
     *     新規 SSE event 到着前に subscribe されている契約。
     *   - replay > 0 にすると Service プロセス再起動時に古い reply 通知が「もう一度」
     *     post されてしまう (P2-6 議論)。kill 経路の通知は OS 側でユーザに届いている
     *     ため二重通知になる。
     *   - START_STICKY redeliver では Service が再 onCreate されるが、その時点の
     *     `_events` 履歴は破棄が正しい (Hub からは SSE 再接続で再送される)。
     */
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

    /** 内部の単一 source: mode/pending/transcript/input/micSource の論理スナップショット。 */
    private val _draft = MutableStateFlow(initialDraft())

    private val seqCounter = AtomicInteger(0)
    private val _currentState = MutableStateFlow(initialCurrentState())
    val currentState: StateFlow<CurrentState> = _currentState.asStateFlow()

    private val settingsFlow = MutableStateFlow(Settings())

    init {
        settings = settingsFlow.asStateFlow()

        // Settings → SettingsStore + ConnectionController に流す
        scope.launch {
            settingsStore.settings.collect { newSettings ->
                settingsFlow.value = newSettings
                connection.update(newSettings)
            }
        }

        // SSE event の処理
        connection.events.onEach(::onSseEvent).launchIn(scope)

        // 単一 source: currentStateDraft の合成。Phone UI と Glass wire の両方の親。
        scope.launch {
            combine(
                sessionStore.snapshot,
                _pendingPermissions,
                _inputText,
            ) { sessionSnap, pending, inputText ->
                val currentSession = sessionSnap.currentSessionId
                val pendingForCurrent = pending.firstOrNull { it.sessionId == currentSession }
                CurrentStateDraft(
                    mode = deriveMode(pendingForCurrent, TranscriptState.IDLE),
                    pendingPermission = pendingForCurrent?.toWirePayload(),
                    transcriptState = TranscriptState.IDLE,
                    transcriptText = "",
                    inputText = inputText,
                    micSource = MicSource.GLASS,
                )
            }
                .distinctUntilChanged()
                .collect { draft ->
                    _draft.value = draft
                    val newSeq = seqCounter.incrementAndGet()
                    val payload = draft.toPayload(seq = newSeq)
                    _currentState.value = payload
                    StructuredLog.phoneStateEmit(payload)
                }
        }

        // uiState は draft + sessions/messages/attachedImage/connectivity を merge して導出。
        // mode は draft 由来なので Glass wire と必ず同じ値を観測する。
        uiState = combine(
            _draft,
            sessionStore.snapshot,
            _pendingPermissions,
            _attachedImage,
            connectivity,
        ) { draft, sessionSnap, pending, image, conn ->
            PhoneUiState(
                sessions = sessionSnap.sessions,
                currentSessionId = sessionSnap.currentSessionId,
                messages = sessionSnap.messages,
                pendingPermissions = pending,
                inputText = draft.inputText,
                attachedImage = image,
                mode = draft.mode,
                transcriptText = draft.transcriptText,
                connectivity = conn,
            )
        }.distinctUntilChanged().let { flow ->
            val state = MutableStateFlow(PhoneUiState())
            flow.onEach { state.value = it }.launchIn(scope)
            state.asStateFlow()
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
            handleSendFailure(
                pending = pending,
                text = text,
                image = image,
                err = SharedWireError.Connection.NotConfigured.asException(),
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
                // FR-PH-55: pending (UNKNOWN bucket / chatId=null) に chat_id + session_id を貼る。
                // これで後続 SseEvent.Reply の SessionStore.mergeUnknownSession が正規 session へ移送できる。
                sessionStore.assignChatIdToPending(pending.id, resp.chatId, resp.sessionId)
                _events.tryEmit(ChannelEvent.Sent(resp.chatId, pending.id))
            }
            .onFailure { err -> handleSendFailure(pending, text, image, err) }
    }

    /**
     * 送信失敗時のロールバック (P2-5): local echo を取り消し input を復元する。
     * Compose 側で "失敗したまま OUTGOING が残る" のを防ぎ、再送可能にする。
     */
    private suspend fun handleSendFailure(
        pending: ChatMessage,
        text: String,
        image: ImageAttachment?,
        err: Throwable,
    ) {
        sessionStore.removePendingMessage(pending.id)
        _inputText.value = text
        if (image != null) _attachedImage.value = image
        emitErrorFromThrowable(err)
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
                    current.filterNot { it.requestId == requestId }
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
                // authority order (= Hub 側 createdAtMs 昇順) で local list を再構築。
                // P2-3: 単に filter すると挿入順しか保たない → reorder する。
                _pendingPermissions.update { current ->
                    val byId = current.associateBy { it.requestId }
                    event.requestIds.mapNotNull { byId[it] }
                }
            }
            is SseEvent.Permission -> {
                val pending = PendingPermission(
                    requestId = event.requestId,
                    sessionId = event.sessionId,
                    toolName = event.toolName,
                    description = event.description,
                    inputPreview = event.inputPreview,
                    // Phone-local 時計。wire に createdAtMs が無いため (P3-6 / 設計書 §4.3.1)
                    // 受信時刻を採用。Hub 側の本物 createdAtMs は permission_snapshot の
                    // request_ids 順序が持つ (§3.2.1.3 注釈)。
                    createdAtMs = System.currentTimeMillis(),
                )
                _pendingPermissions.update { current ->
                    if (current.any { it.requestId == event.requestId }) current
                    else current + pending
                }
                _events.tryEmit(ChannelEvent.PermissionRequested(pending))
            }
            is SseEvent.PermissionAbort -> {
                _pendingPermissions.update { current ->
                    val filtered = current.filterNot { it.requestId == event.requestId }
                    if (filtered.size == current.size) current else filtered
                }
                _errors.tryEmit(
                    TransientError.Shared(SharedWireError.Permission.Aborted(event.requestId)),
                )
            }
            is SseEvent.Reply -> {
                _events.tryEmit(ChannelEvent.Reply(event.chatId, event.sessionId, event.text))
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
        pendingForCurrent: PendingPermission?,
        transcriptState: TranscriptState,
    ): ConversationMode = when {
        pendingForCurrent != null -> ConversationMode.PERMISSION_CONFIRMING
        transcriptState == TranscriptState.LISTENING -> ConversationMode.LISTENING
        else -> ConversationMode.IDLE
    }

    private fun initialDraft(): CurrentStateDraft = CurrentStateDraft(
        mode = ConversationMode.IDLE,
        pendingPermission = null,
        transcriptState = TranscriptState.IDLE,
        transcriptText = "",
        inputText = "",
        micSource = MicSource.GLASS,
    )

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
        val pendingPermission: PendingPermissionPayload?,
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

/**
 * 一過性イベント (UI が SnackBar / 通知トリガに使う)。
 *
 * `Reply.text` / `PermissionRequested.pending` をペイロードとして同梱するのは、
 * ChannelService が通知を作る際に sessionStore / pendingPermissions を別途
 * lookup する race を避けるため (PermissionRequested は emit 時点では list に
 * 入っているが、reply は SessionStore にも到達しており、UI 観点の "返信文" は
 * その場で値が固まっている event のほうが安全)。
 */
sealed class ChannelEvent {
    data class Reply(val chatId: String, val sessionId: String?, val text: String) : ChannelEvent()
    data class PermissionRequested(val pending: PendingPermission) : ChannelEvent()
    data class Sent(val chatId: String, val localMessageId: Long) : ChannelEvent()
}
