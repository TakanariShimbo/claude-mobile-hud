package com.example.claudemobilehud.phone.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.error.TransientError
import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.data.model.ConnectivityState
import com.example.claudemobilehud.phone.data.model.ImageAttachment
import com.example.claudemobilehud.phone.data.model.PendingPermission
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.Settings
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.phone.data.transcription.TranscriptionClient
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
import kotlinx.coroutines.withContext
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
 * data 層 facade (docs/03 §3.2.1)。
 *
 * 単一 source `_draft` から `currentState` (Glass wire) と `uiState` (Phone UI) を
 * 派生させ、mode の観測ずれを排除する (NFR-13 / docs/02 §4.5)。
 */
class ChannelRepository(
    private val applicationContext: Context,
    historyFilesDir: File = applicationContext.filesDir,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    audioRouter: InputController.AudioRouter? = null,
) {
    private val log = StructuredLog("channel.repo")
    private val settingsStore = SettingsStore(applicationContext)
    private val historyStore = HistoryStore(historyFilesDir)
    private val sessionStore = SessionStore(historyStore)
    private val connection = ConnectionController()
    // public はテスト容易性のため。UI から `input.transcription.start(...)` を直接呼ぶのは
    // 設計外 (ChannelRepository の start*/stop* 経由を使う)。
    val input: InputController = InputController(audioRouter = audioRouter, scope = scope)

    // 公開 flow
    val settings: StateFlow<Settings>
    val connectivity: StateFlow<ConnectivityState> get() = connection.status
    val uiState: StateFlow<PhoneUiState>

    val inputText: StateFlow<String> get() = input.text

    private val _attachedImage = MutableStateFlow<ImageAttachment?>(null)
    val attachedImage: StateFlow<ImageAttachment?> get() = _attachedImage.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingPermissions: StateFlow<List<PendingPermission>> get() = _pendingPermissions.asStateFlow()

    // session 単位の「送信確認モード」flag。priority / lifecycle は docs/03 §3.2.1.2.1-2 参照。
    private val _confirmingBySession = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // replay=0: Service プロセス再起動時に古い reply 通知が二重 post されるのを防ぐ
    // (Hub 側 SSE 再接続で必要な event は再送される)。
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

        connection.events.onEach(::onSseEvent).launchIn(scope)

        input.errors
            .onEach { _errors.tryEmit(TransientError.Phone(it)) }
            .launchIn(scope)

        // combine は 5 引数までなので nested に重ねている (Pair/data class を増やさない方針)。
        scope.launch {
            val inputs = combine(
                input.text,
                input.transcription.state,
                input.micSource,
            ) { text, transState, micSource -> Triple(text, transState, micSource) }
            combine(
                sessionStore.snapshot,
                _pendingPermissions,
                _confirmingBySession,
                inputs,
            ) { sessionSnap, pending, confirmingMap, inputTriple ->
                val (inputText, transState, micSource) = inputTriple
                val currentSession = sessionSnap.currentSessionId
                val pendingForCurrent = pending.firstOrNull { it.sessionId == currentSession }
                val transcriptState = transState.toWireState()
                val transcriptText = (transState as? TranscriptionClient.State.Listening)?.partial.orEmpty()
                val confirmingForCurrent = currentSession?.let { confirmingMap[it] } == true
                CurrentStateDraft(
                    mode = deriveMode(pendingForCurrent, transcriptState, confirmingForCurrent),
                    pendingPermission = pendingForCurrent?.toWirePayload(),
                    transcriptState = transcriptState,
                    transcriptText = transcriptText,
                    inputText = inputText,
                    micSource = micSource,
                    confirming = confirmingForCurrent,
                )
            }
                .distinctUntilChanged()
                .collect { draft ->
                    _draft.value = draft
                    val newSeq = seqCounter.incrementAndGet()
                    val payload = draft.toPayload(seq = newSeq)
                    _currentState.value = payload
                    // AC-09 verifier (tools/verify_atomicity.py) が読む event。
                    StructuredLog.phoneStateEmit(payload, draft.confirming)
                }
        }

        uiState = combine(
            _draft,
            sessionStore.snapshot,
            _pendingPermissions,
            _attachedImage,
            connectivity,
        ) { draft, sessionSnap, pending, image, conn ->
            // 現 session 宛 pending は Repository 側で計算して UI に渡す (NFR-51: UI が
            // 状態管理ロジックを持たない)。
            val pendingForCurrent = sessionSnap.currentSessionId?.let { cid ->
                pending.filter { it.sessionId == cid }
            }.orEmpty()
            PhoneUiState(
                sessions = sessionSnap.sessions,
                currentSessionId = sessionSnap.currentSessionId,
                messages = sessionSnap.messages,
                pendingPermissions = pending,
                pendingForCurrent = pendingForCurrent,
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
        input.setCurrentSession(sessionStore.snapshot.value.currentSessionId)
    }

    // --- 公開 action ---

    suspend fun saveSettings(s: Settings) {
        settingsStore.save(s)
    }

    fun reconnect() = connection.reconnect()

    suspend fun selectSession(sessionId: String) {
        sessionStore.selectSession(sessionId)
        settingsStore.saveLastCurrentSessionId(sessionId)
        input.setCurrentSession(sessionId)
    }

    suspend fun deleteSession(sessionId: String) = sessionStore.deleteSession(sessionId)

    /** 「送信確認モード」flag (docs/03 §3.2.1.2.2 の lifecycle 表)。 */
    fun setConfirming(sessionId: String?, value: Boolean) {
        val id = sessionId ?: sessionStore.snapshot.value.currentSessionId ?: return
        _confirmingBySession.update { map ->
            if (value) map + (id to true) else map - id
        }
    }

    fun updateInputText(text: String) {
        input.update(text)
    }

    fun clearInput() {
        input.clear()
        _attachedImage.value = null
    }

    // --- transcription 公開 API (Phase 3 §3.2.1.1) ---

    fun startTranscriptionPhoneMic() {
        val key = settingsFlow.value.openAiApiKey
        if (key.isBlank()) {
            _errors.tryEmit(TransientError.Phone(PhoneWireError.Transcription.ApiKeyMissing))
            return
        }
        input.startWithPhoneMic(key)
    }

    fun startTranscriptionFromGlass() {
        val key = settingsFlow.value.openAiApiKey
        if (key.isBlank()) {
            _errors.tryEmit(TransientError.Phone(PhoneWireError.Transcription.ApiKeyMissing))
            return
        }
        input.startFromGlass(key)
    }

    fun stopTranscription() = input.stop()

    /** 失敗は wire-typed exception で `_errors` に流す (UI snackbar が localized message を出せる)。 */
    suspend fun attachImageFromUri(uri: Uri) {
        runCatching { ImageProcessor.encode(applicationContext, uri) }
            .onSuccess { image ->
                _attachedImage.value?.let { previous ->
                    runCatching { File(previous.localPath).delete() }
                }
                _attachedImage.value = image
            }
            .onFailure { emitErrorFromThrowable(it) }
    }

    fun attachImage(image: ImageAttachment) {
        _attachedImage.value?.let { previous ->
            if (previous.localPath != image.localPath) {
                runCatching { File(previous.localPath).delete() }
            }
        }
        _attachedImage.value = image
    }

    fun clearAttachedImage() {
        // 送信成功経路では Bridge staging が複製済みなので Phone-local cache は破棄してよい。
        _attachedImage.value?.let { previous ->
            runCatching { File(previous.localPath).delete() }
        }
        _attachedImage.value = null
    }

    /** 現在 session 宛にテキスト送信。chat_id は Hub mint。 */
    suspend fun send(text: String) {
        if (text.isBlank() && _attachedImage.value == null) return
        // AuthFailed なら 401 で確実に弾かれるので早期 return。OUTGOING の append → rollback
        // による入力欄 flapping を防ぐ。
        if (connection.status.value == ConnectivityState.AuthFailed) {
            _errors.tryEmit(TransientError.Shared(SharedWireError.Connection.AuthFailed))
            return
        }
        input.stop()
        val sessionId = sessionStore.snapshot.value.currentSessionId
        val image = _attachedImage.value
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
        val imageBase64 = try {
            image?.let { encodeImageBase64(it) }
        } catch (err: Throwable) {
            handleSendFailure(pending, text, image, err)
            return
        }
        val result = client.send(
            text = text,
            sessionId = sessionId,
            image = image,
            imageBase64 = imageBase64,
        )
        result
            .onSuccess { resp -> onSendSuccess(resp, sessionId, pending) }
            .onFailure { err -> handleSendFailure(pending, text, image, err) }
    }

    // 失敗は throw → caller (send) が handleSendFailure でロールバックする契約。
    private suspend fun encodeImageBase64(image: ImageAttachment): String =
        withContext(Dispatchers.IO) {
            val bytes = File(image.localPath).readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    private suspend fun onSendSuccess(
        resp: ChannelClient.SendResponse,
        requestSessionId: String?,
        pending: ChatMessage,
    ) {
        log.info(
            "send_ok",
            "chat_id" to resp.chatId,
            "session_id" to (resp.sessionId ?: ""),
        )
        sessionStore.assignChatIdToPending(pending.id, resp.chatId, resp.sessionId)
        // Hub mint された sessionId 優先で flag を畳む (旧 null キーが残らないように)。
        resp.sessionId?.let { setConfirming(it, false) } ?: setConfirming(requestSessionId, false)
        _events.tryEmit(ChannelEvent.Sent(resp.chatId, pending.id))
    }

    private suspend fun handleSendFailure(
        pending: ChatMessage,
        text: String,
        image: ImageAttachment?,
        err: Throwable,
    ) {
        sessionStore.removePendingMessage(pending.id)
        input.update(text)
        if (image != null) _attachedImage.value = image
        emitErrorFromThrowable(err)
    }

    suspend fun respondPermission(requestId: String, behavior: PermissionDecision) {
        // AuthFailed では 401 で弾かれるので早期 return (send() と同じ flap 抑止)。
        if (connection.status.value == ConnectivityState.AuthFailed) {
            _errors.tryEmit(TransientError.Shared(SharedWireError.Connection.AuthFailed))
            return
        }
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
                // Hub 側 createdAtMs 昇順を reorder で local list に反映 (単純 filter だと
                // 挿入順しか保たない)。
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
                    // wire に createdAtMs が無いため受信時刻で代用 (docs/03 §4.3.1)。
                    // 真の Hub 側順序は permission_snapshot.request_ids が持つ。
                    createdAtMs = System.currentTimeMillis(),
                )
                _pendingPermissions.update { current ->
                    if (current.any { it.requestId == event.requestId }) current
                    else current + pending
                }
                event.sessionId?.let { maybeAutoSwitchSession(it) }
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
                event.sessionId?.let { maybeAutoSwitchSession(it) }
                _events.tryEmit(ChannelEvent.Reply(event.chatId, event.sessionId, event.text))
            }
            else -> Unit
        }
    }

    /**
     * IDLE のときだけ別 session に切替える (docs/03 §3.2.1.3.1 — race 整理含む)。
     * 録音中 / 送信確認中 / 権限確認中はユーザを邪魔しないため触らない。
     */
    private suspend fun maybeAutoSwitchSession(targetSessionId: String) {
        val current = sessionStore.snapshot.value.currentSessionId
        if (current == targetSessionId) return
        if (_draft.value.mode != ConversationMode.IDLE) return
        selectSession(targetSessionId)
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

    /** Mode 優先順位は docs/03 §3.2.1.2.1。 */
    private fun deriveMode(
        pendingForCurrent: PendingPermission?,
        transcriptState: TranscriptState,
        confirmingForCurrent: Boolean,
    ): ConversationMode = when {
        transcriptState == TranscriptState.LISTENING -> ConversationMode.LISTENING
        pendingForCurrent != null -> ConversationMode.PERMISSION_CONFIRMING
        confirmingForCurrent -> ConversationMode.CONFIRMING
        else -> ConversationMode.IDLE
    }

    // Connecting / Error は wire 上 IDLE (Glass HUD で partial を表示するのは Listening のみ)。
    private fun TranscriptionClient.State.toWireState(): TranscriptState = when (this) {
        is TranscriptionClient.State.Listening -> TranscriptState.LISTENING
        else -> TranscriptState.IDLE
    }

    private fun initialDraft(): CurrentStateDraft = CurrentStateDraft(
        mode = ConversationMode.IDLE,
        pendingPermission = null,
        transcriptState = TranscriptState.IDLE,
        transcriptText = "",
        inputText = "",
        micSource = MicSource.GLASS,
        confirming = false,
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
        // mode に折り込み済みだが、AC-09 verifier が CONFIRMING の正当性検査用に key として
        // 読むため draft に明示保持する (StructuredLog.phoneStateEmit 第 2 引数)。
        val confirming: Boolean,
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
 * 一過性イベント (UI / 通知トリガ用)。payload を event に同梱するのは、ChannelService が
 * 通知を作る際に sessionStore / pendingPermissions を再 lookup する race を避けるため。
 */
sealed class ChannelEvent {
    data class Reply(val chatId: String, val sessionId: String?, val text: String) : ChannelEvent()
    data class PermissionRequested(val pending: PendingPermission) : ChannelEvent()
    data class Sent(val chatId: String, val localMessageId: Long) : ChannelEvent()
}
