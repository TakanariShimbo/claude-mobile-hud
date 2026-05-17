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
    audioRouter: InputController.AudioRouter? = null,
) {
    private val log = StructuredLog("channel.repo")
    private val settingsStore = SettingsStore(applicationContext)
    private val historyStore = HistoryStore(historyFilesDir)
    private val sessionStore = SessionStore(historyStore)
    private val connection = ConnectionController()
    // 4b2: input + transcription を InputController に集約。AudioRouter は 4c で注入。
    // P3-1: UI から transcription を直接駆動できてしまう (`repository.input.transcription.start(...)`)
    // のは設計外。テスト容易性のため public のまま残すが、ProductionGuard 的な分離は
    // 4c の MainActivity/ViewModel で隠す。
    val input: InputController = InputController(audioRouter = audioRouter, scope = scope)

    // 公開 flow
    val settings: StateFlow<Settings>
    val connectivity: StateFlow<ConnectivityState> get() = connection.status
    val uiState: StateFlow<PhoneUiState>

    /** 入力欄テキスト。Repository.input.text の facade (互換 API 用)。 */
    val inputText: StateFlow<String> get() = input.text

    private val _attachedImage = MutableStateFlow<ImageAttachment?>(null)
    val attachedImage: StateFlow<ImageAttachment?> get() = _attachedImage.asStateFlow()

    private val _pendingPermissions = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingPermissions: StateFlow<List<PendingPermission>> get() = _pendingPermissions.asStateFlow()

    /**
     * session 単位の「送信確認モード」フラグ (POC port)。Glass の TAP (Listening 停止)
     * で当該 session を true、SWIPE_FORWARD/SWIPE_BACK (送信/取消) もしくは send 成功で
     * false に倒す。session を跨いでも保持されるので、別 session で作業して戻ってきた
     * 際に未確定の Confirming 状態が復元される。優先順位は LISTENING > PERMISSION_CONFIRMING
     * > CONFIRMING > IDLE (POC と同じ — 録音中は permission/confirming で上書きしない)。
     */
    private val _confirmingBySession = MutableStateFlow<Map<String, Boolean>>(emptyMap())

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

        // InputController 由来のエラー (BtScoUnavailable 等) を UI 層に転送する (P1-3 of 4b2)。
        input.errors
            .onEach { _errors.tryEmit(TransientError.Phone(it)) }
            .launchIn(scope)

        // 単一 source: currentStateDraft の合成。Phone UI と Glass wire の両方の親。
        // 4b2 で transcription state / micSource を InputController 由来に切替え。
        // POC 移植: `_confirmingBySession` も入力に加えるが、`combine` 5 引数 lambda の
        // 限界を超えるので「入力 (transcription/inputText/micSource) と外部 state (session/
        // pending/confirming) の 2 つの combine を nested に重ねる」形にする。型安全 + 既存
        // 構造との整合性のため、Pair / data class は導入しない。
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
            // P1-6 of AC-05: 現 session 宛の pending は Repository 側で filter。UI から
            // この計算を吹き飛ばすため `pendingForCurrent` フィールドに乗せて渡す。
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

    /**
     * 指定 session の「送信確認モード」flag を更新 (POC port)。
     * `sessionId == null` のときは current。`GlassEventDispatcher` が gesture handler から呼ぶ:
     *   - Listening → TAP 停止時に `setConfirming(currentSession, true)` → CONFIRMING mode 出現
     *   - SWIPE_FORWARD/BACK で `setConfirming(currentSession, false)` → IDLE / 次 mode へ
     * `send()` 成功時にも flag を畳む (送信完了で確認モード終了)。
     */
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

    /**
     * P2-E of 4c2 review: Uri → ImageProcessor.encode を Repository に集約し、
     * 失敗は wire-typed exception 経由で `_errors` flow に流す。UI 側で
     * `IllegalArgumentException.message` を直接 snackbar に出す古い経路だと
     * localized message と一貫しなかった (MainScreenEffects.toUserMessage 経路と
     * 揃える)。
     */
    suspend fun attachImageFromUri(uri: Uri) {
        runCatching { ImageProcessor.encode(applicationContext, uri) }
            .onSuccess { image ->
                // P2-F: 既に持っていた cache file を破棄してから差し替える。
                _attachedImage.value?.let { previous ->
                    runCatching { File(previous.localPath).delete() }
                }
                _attachedImage.value = image
            }
            .onFailure { emitErrorFromThrowable(it) }
    }

    fun attachImage(image: ImageAttachment) {
        // P2-F: 直接差し替え経路でも前 cache を消す。
        _attachedImage.value?.let { previous ->
            if (previous.localPath != image.localPath) {
                runCatching { File(previous.localPath).delete() }
            }
        }
        _attachedImage.value = image
    }

    fun clearAttachedImage() {
        // P2-F of 4c2 review: 添付取消時に cache file もここで削除する。
        // 送信成功経路 (clearInput) では Bridge が staging で複製済みなので、
        // Phone-local cache は不要になっている。失敗時は handleSendFailure が
        // 再設定するので、ここで delete されないルートを通る。
        _attachedImage.value?.let { previous ->
            runCatching { File(previous.localPath).delete() }
        }
        _attachedImage.value = null
    }

    /**
     * 現在 session 宛にテキスト送信。chat_id は Hub mint。失敗時は events / errors に emit。
     * 4b で image (base64) 添付に対応 (現状は path のみ持つ Attachment を ignore)。
     */
    suspend fun send(text: String) {
        if (text.isBlank() && _attachedImage.value == null) return
        // P2-1 of 4d review: AuthFailed のときは送信を試みても 401 で確実に弾かれるので
        // 早期 return + AuthFailed error emit。これで OUTGOING の flap (appendOutgoing →
        // handleSendFailure の往復で input/image が一瞬消えて戻る) を防ぎ、連打 send 時に
        // 入力が flapping するのを止める。ユーザは SettingsDialog (auto open) で token を
        // 直し、Connectivity が Open に戻ってから再 send する。
        if (connection.status.value == ConnectivityState.AuthFailed) {
            _errors.tryEmit(TransientError.Shared(SharedWireError.Connection.AuthFailed))
            return
        }
        // 送信開始の時点で transcription 中なら停止 (これ以上の partial を input に書き込まない)。
        input.stop()
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

    /**
     * P1-7 of AC-05: 画像 base64 化を `send()` から抽出。`ImageProcessor` は localPath
     * だけ持つ `ImageAttachment` を返す (`ImageProcessor.encode` 注釈) ので、Hub に
     * `image_base64` + `image_mime` で渡すために送信直前で file → base64 する。
     * 読み込み失敗 (cache 削除 / 容量 etc) は throw して caller (`send()`) が
     * `handleSendFailure` でロールバックする契約。Hub 側 body 上限は 16MB
     * (base64 込み)、Phone 側 11MB raw で fail-fast する (`ImageProcessor.MAX_BYTES`)。
     */
    private suspend fun encodeImageBase64(image: ImageAttachment): String =
        withContext(Dispatchers.IO) {
            val bytes = File(image.localPath).readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    /**
     * P1-7 of AC-05: 送信成功時の副作用 3 件を `send()` から抽出。
     *   - FR-PH-55: pending (UNKNOWN bucket / chatId=null) に chat_id + session_id を貼る。
     *     後続 `SseEvent.Reply` で `SessionStore.mergeUnknownSession` が正規 session
     *     へ移送する材料になる。
     *   - POC port: confirming flag を畳む (Glass の CONFIRMING UI から抜ける)。
     *     `resp.sessionId` (Hub mint された正規 id) を優先し、null の場合のみ送信時の
     *     snapshot にフォールバック (UNKNOWN bucket → 新規 session mint 経路で、古い
     *     `sessionId == null` キーに confirming が残らないようにする、P2-B of 4d review)。
     *   - ChannelEvent.Sent emit で UI 側 snackbar / 通知トリガに材料を流す。
     */
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
        resp.sessionId?.let { setConfirming(it, false) } ?: setConfirming(requestSessionId, false)
        _events.tryEmit(ChannelEvent.Sent(resp.chatId, pending.id))
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
        input.update(text)
        if (image != null) _attachedImage.value = image
        emitErrorFromThrowable(err)
    }

    suspend fun respondPermission(requestId: String, behavior: PermissionDecision) {
        // send() と同じく AuthFailed のときは早期 return。verdict 送信は 401 で必ず弾かれ、
        // outstanding は Hub 側に残ったまま (= 別経路で resolved になるまでフラッシュされない)。
        // 通知シェードからの ALLOW/DENY 連打を flapping させない。
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
                // POC port: 現在 mode が IDLE のときだけ別 session の permission に auto-switch
                // (ユーザが録音/確認中/別 permission 対応中なら邪魔しない gating)。
                // sessionId が null (Hub-side で session 未確定) は対象外。
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
                // POC port: Reply auto-switch も permission と同じ gating。session_id が無い
                // (UNKNOWN_SESSION_ID 経路) reply は対象外。
                event.sessionId?.let { maybeAutoSwitchSession(it) }
                _events.tryEmit(ChannelEvent.Reply(event.chatId, event.sessionId, event.text))
            }
            else -> Unit
        }
    }

    /**
     * POC port: IDLE のときだけ別 session に切替える。録音中 (LISTENING) / 送信確認中
     * (CONFIRMING) / 権限確認中 (PERMISSION_CONFIRMING) は触らない。同じ session への
     * 切替は noop。
     *
     * P2-C of review: 呼び出し順との関係について。本関数は `_pendingPermissions.update {...}`
     * 直後 (Permission 経路) または SessionStore 適用直後 (Reply 経路) に呼ばれる。
     * `_draft.value.mode` は combine 経由で非同期更新だが、判定は下記の通り安全:
     *   - `targetSessionId == current` の場合は冒頭 early-return で抜ける。
     *   - `target != current` の場合、新規 pending は `pendingForCurrent` には入らない
     *     (current の filter で落ちる) ため、permission 追加で current の mode が
     *     `PERMISSION_CONFIRMING` に変わることはなく、`_draft.value.mode` の遅延が
     *     ここでの IDLE 判定を誤らせる経路は存在しない。
     *   - Reply 経路は mode に影響しないので同様に安全。
     * つまり _draft の更新タイミングに関係なく、ここで観測する mode は target への切替を
     * 抑止すべきかどうかの正しい判断材料になる。
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

    /**
     * Mode の優先順位 (POC と同じ): `LISTENING > PERMISSION_CONFIRMING > CONFIRMING > IDLE`。
     * 録音中はユーザの作業を邪魔しないため permission / confirming で上書きしない設計。
     */
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

    /**
     * TranscriptionClient.State (UI / facade 視点) を wire 上の [TranscriptState] へ写像。
     *   - `Idle` / `Error` → `IDLE` (Error は UI 側で snackbar 等で表示)。
     *   - `Connecting` → `IDLE` (まだ partial が無いため Glass HUD は通常表示で良い)。
     *   - `Listening` → `LISTENING`。
     */
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
