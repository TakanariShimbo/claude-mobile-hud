# Phase 3: 詳細設計

`claude-mobile-hud` v1.0 の **内部設計**。Phase 2 で interface / 横断判断を固めた後、各コンポーネントの**クラス構造 / 状態遷移 / 永続化スキーマ / 主要 algorithm** を Phase 4 (実装) が手戻りなく着手できる粒度まで詳細化する。

- **作成日**: 2026-05-16
- **改訂**: Rev 3 (Rev 2 への再レビュー反映 — Phase 4 進行可判定後の最終調整)
- **依存**: Phase 1 (`01-requirements.md` Rev 4→Rev 5), Phase 2 (`02-architecture.md` Rev 4)

> **本書と同じ commit で Phase 1 を Rev 5 化**: FR-HU-14 文言を「permission_snapshot 形式での再 push (createdAtMs 昇順)」に書き換える。本書 §1.2 / §5.2.3 / §11 と整合させるため。

---

## 1. 目的と Phase 2 からの引き継ぎ

### 1.1 この文書の役割

Phase 2 で「コンポーネントの interface」が決まった。本文書では:

- 各コンポーネントの **パッケージ / モジュール構造**
- 重要クラスの **公開 API (シグネチャ)** と内部状態
- StateFlow / SharedFlow の **combine 構造 / バッファ policy** (NFR-13 を達成するための flow 接続)
- 永続化 **スキーマ** (履歴 JSON / DataStore キー)
- State machine の **遷移表 + 実装方針** (Kotlin / TS 疑似コード)
- Phase 2 §9.4 の Phase 3 持ち越し論点 (4 件) への結論

を定める。**実コード** (関数ボディや実装詳細) は Phase 4 で書く。

### 1.2 Phase 2 §9.4 D-高: Hub 再起動時 outstanding の扱い (AD-13)

**問題**: Hub プロセスがクラッシュ + 再起動すると、in-memory の outstanding state がロストする。Bridge は再接続できるが `request_id ↔ bridge` map と pending 状態は消える。Phone は local の pending UI を持ち続けて「永遠の幽霊 pending」が発生しうる。

**決定 (AD-13)**: **SSE 再接続時、Hub からの `permission_snapshot` を authoritative とし、Phone は受信内容に絞り込む形で local pending を再構築する**。

具体ルール:
1. Hub は SSE 再接続成立後、**`session_snapshot` (FR-HU-05) → `permission_snapshot` (新規) → 通常イベント** の順で push
2. **新規 wire `permission_snapshot`**: `{ request_ids: [...] }` (現在 outstanding な全ての request_id を **createdAtMs 昇順** で含む)
3. Phone は `permission_snapshot` 受信時、local `_pendingPermissions` を「snapshot に含まれる request_id のみ」に**新規 List 参照で絞り込み** (combine の `distinctUntilChanged` を確実に通すため)
4. その後の `permission` イベントで個別 push されたものを冪等に追加 (FR-PH-46)
5. Hub 再起動直後は outstanding 0 件 → `permission_snapshot { request_ids: [] }` を送る → Phone の幽霊 pending が一掃される

**Phone→Glass 波及保証** (Rev 1 レビュー指摘の修正):
- Phone が `_pendingPermissions` を更新すると `currentState` の combine flow が新値を emit
- `currentState` は `MutableStateFlow` ベースのため、collector ですでに seq が増えた新値が `_currentState.value` に書き込まれる → 即座に GlassRelay が新 `current_state(seq=N+1)` を Glass に push
- Glass は新 seq の current_state を受信して `pendingPermission=null` を反映 → 幽霊 pending UI が消える
- 重要: `_pendingPermissions.update {}` で**同一 List 参照を返してはならない**。`distinctUntilChanged` が抑制すると Glass に伝わらない

**FR への影響**: Phase 1 FR-HU-14 を「snapshot 形式での再 push」に書き換える (Phase 1 Rev 5、本 commit 同梱)。

**代替案と却下理由**:
- Phone が Hub に「この request_id まだ生きてる?」と個別問い合わせ: 新規 wire + 双方向 round-trip 追加で複雑
- Phone がタイムアウト (例: 30 秒応答無しで自動 deny): タイマー / 時刻同期に依存、誤動作リスク
- Hub の永続化 (disk への保存): 個人ツール規模で Hub に DB を持ち込む overkill

### 1.3 Phase 2 §9.4 D-中 / D-低 への結論

| 項目 | 結論 | 詳細 |
|---|---|---|
| Bridge `register` 確定前の race (D-中) | **Hub は register 受信前のメッセージを reject + log warn**。Bridge 側は register が ack されるまで送信を保留する protocol に修正 | §6.3 |
| 通知 channel id 変更時 (D-中) | **v1.0 期間中は channel id 固定**。アップグレード時に変更不可とし、必要なら v2.0 でマイグレーション設計 | §3.6.3 |
| outstanding 再 push の順序 (D-低) | **Hub は `createdAtMs` 昇順で push**。Phone 側も snapshot の order を活用して `_pendingPermissions` を rebuild | §3.2.1.3, §5.2.3 |

### 1.4 Rev 2 で追加した設計判断 (Rev 1 レビュー反映)

| AD | 内容 | 詳細 |
|---|---|---|
| AD-14 | Phone Repository の SharedFlow / StateFlow buffer policy | §3.2.1.4 |
| AD-15 | NFR-13 atomicity の射程は currentState (mode + 関連 payload) のみ。session_list / messages は eventual consistency | §3.4.1 |
| AD-16 | VerdictDispatchService は FGS-dataSync に格上げ。NFR-14 5s 予算表で根拠提示 | §3.3.5 |
| AD-17 | HistoryStore は `.tmp + rename` の atomic write。shutdownAll で flush を保証 | §3.6.1 |
| AD-18 | Compose recomposition 戦略: `PhoneUiState` を直接 collect、UI 局所性は `derivedStateOf` で抽出 | §3.5.3 |
| AD-19 | 構造化ログのタグ規約 / レベル基準 | §8 |
| AD-20 | Phase 4 リポジトリ物理配置 + Gradle root 構成 | §9 |
| AD-21 | WireError のうち `Connection` / `Permission` 系を `:protocol` に昇格、Phone / Glass で共有 | §3.7, §2.4 |

---

## 2. shared module `:protocol` 詳細設計

### 2.1 Gradle / モジュール構造

```
:protocol/                  ← Kotlin library module (no Android dependency)
├── build.gradle.kts
└── src/
    ├── main/kotlin/
    │   └── com/example/claudemobilehud/protocol/
    │       ├── WireEvent.kt        ← sealed interface + 全イベント data class
    │       ├── WireField.kt        ← フィールド名定数 (snake_case)
    │       ├── WireEnum.kt         ← 全 enum
    │       ├── codec/
    │       │   ├── Codec.kt
    │       │   ├── JsonCodec.kt
    │       │   └── CapsCodec.kt
    │       └── error/
    │           ├── ProtocolError.kt   ← codec 失敗時
    │           └── SharedWireError.kt ← Connection / Permission 系 (AD-21)
    └── test/kotlin/
        ├── KotlinGoldenGenerator.kt
        ├── TsGoldenVerifier.kt
        └── golden/
            ├── kotlin/*.json
            └── ts/*.json
```

`:protocol` は Android 依存を持たない (`android.*` を import しない) ため JVM テストでフルに検証可能。Phone / Glass の app module は `implementation(project(":protocol"))` で参照する。

### 2.2 WireEvent sealed hierarchy 完全版

Phase 2 §5.1 の論理型を、`@Serializable` 付きで実体化。

```kotlin
package com.example.claudemobilehud.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
sealed interface WireEvent {
    val ts: Long
}

// --- Phone↔Glass: state push ---

@Serializable
@SerialName("current_state")
data class CurrentState(
    val seq: Int,
    val mode: ConversationMode,
    @SerialName("pending_permission") val pendingPermission: PendingPermissionPayload?,
    @SerialName("transcript_state") val transcriptState: TranscriptState,
    @SerialName("transcript_text") val transcriptText: String,
    @SerialName("input_text") val inputText: String,
    @SerialName("mic_source") val micSource: MicSource,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("input_text_only")
data class InputTextOnly(
    @SerialName("parent_seq") val parentSeq: Int,
    @SerialName("input_text") val inputText: String,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("session_list")
data class SessionList(
    val sessions: List<SessionSummaryPayload>,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("current_session")
data class CurrentSessionEvent(val id: String?, override val ts: Long) : WireEvent

@Serializable
@SerialName("messages")
data class MessagesEvent(
    @SerialName("session_id") val sessionId: String?,
    val messages: List<ChatMessagePayload>,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("notification")
data class NotificationEvent(
    val kind: NotificationKind,
    val text: String,
    @SerialName("session_id") val sessionId: String?,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("error")
data class ErrorEvent(val message: String, override val ts: Long) : WireEvent

// --- Glass→Phone ---

@Serializable
@SerialName("hello")
data class Hello(override val ts: Long) : WireEvent

@Serializable
@SerialName("select_session")
data class SelectSession(val id: String, override val ts: Long) : WireEvent

@Serializable
@SerialName("gesture")
data class GestureEvent(val which: GestureKind, override val ts: Long) : WireEvent

@Serializable
@SerialName("listening_cancel")
data class ListeningCancel(override val ts: Long) : WireEvent

@Serializable
@SerialName("permission_verdict")
data class PermissionVerdictEvent(
    @SerialName("request_id") val requestId: String,
    val decision: PermissionDecision,
    override val ts: Long,
) : WireEvent

// --- CXR session lifecycle ---

@Serializable
@SerialName("session_open")
data class SessionOpen(override val ts: Long) : WireEvent

@Serializable
@SerialName("session_close")
data class SessionClose(override val ts: Long) : WireEvent

@Serializable
@SerialName("ping")
data class Ping(override val ts: Long) : WireEvent

// --- 補助 payload data class ---

@Serializable
data class PendingPermissionPayload(
    @SerialName("request_id") val requestId: String,
    @SerialName("tool_name") val toolName: String,
    val description: String,
    @SerialName("input_preview") val inputPreview: String,
    @SerialName("session_id") val sessionId: String?,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

@Serializable
data class SessionSummaryPayload(
    val id: String,
    val label: String,
    @SerialName("message_count") val messageCount: Int,
)

@Serializable
data class ChatMessagePayload(
    val id: Long,
    val role: MessageRole,
    val text: String,
    @SerialName("chat_id") val chatId: String?,
)
```

### 2.3 enum 定義

```kotlin
@Serializable enum class ConversationMode {
    @SerialName("idle") IDLE,
    @SerialName("listening") LISTENING,
    @SerialName("confirming") CONFIRMING,
    @SerialName("permission_confirming") PERMISSION_CONFIRMING,
}

@Serializable enum class TranscriptState {
    @SerialName("idle") IDLE,
    @SerialName("connecting") CONNECTING,
    @SerialName("listening") LISTENING,
    @SerialName("error") ERROR,
}

@Serializable enum class NotificationKind {
    @SerialName("reply") REPLY,
    @SerialName("permission") PERMISSION,
}

@Serializable enum class GestureKind {
    @SerialName("tap") TAP,
    @SerialName("double_tap") DOUBLE_TAP,
    @SerialName("swipe_forward") SWIPE_FORWARD,
    @SerialName("swipe_back") SWIPE_BACK,
}

@Serializable enum class PermissionDecision {
    @SerialName("allow") ALLOW,
    @SerialName("deny") DENY,
}

@Serializable enum class MessageRole {
    @SerialName("out") OUTGOING,
    @SerialName("in") INCOMING,
    @SerialName("sys") SYSTEM,
}

@Serializable enum class MicSource {
    @SerialName("glass") GLASS,
    @SerialName("phone_fallback") PHONE_FALLBACK,
}
```

### 2.4 共有 WireError (AD-21)

Phone / Glass で共有する error 型を `:protocol` に置く。Phase 4 で実装時に同等型を両 app で参照可能にする。

```kotlin
package com.example.claudemobilehud.protocol.error

sealed class SharedWireError(open val message: String) {
    sealed class Connection(message: String) : SharedWireError(message) {
        data object NotConfigured : Connection("Settings not configured")
        data class ConnectFailed(val causeMessage: String?) : Connection("Connect failed: ${causeMessage ?: "unknown"}")
        data object AuthFailed : Connection("Authentication failed (HTTP 401)")
        data class ServerError(val httpCode: Int, val bodyHead: String) : Connection("HTTP $httpCode: $bodyHead")
    }
    sealed class Permission(message: String) : SharedWireError(message) {
        data class Aborted(val requestId: String) : Permission("Permission $requestId aborted")
        data object AlreadyVerdicted : Permission("Verdict already sent or unknown request")
        data class Unknown(val requestId: String) : Permission("Unknown request_id: $requestId")
    }
}
```

`:protocol` に置かない error (Send / Transcription / Glass-specific) は Phone / Glass それぞれの app 内で定義 (§3.7)。

### 2.5 Codec interface

```kotlin
package com.example.claudemobilehud.protocol.codec

interface Codec {
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}

object JsonCodec : Codec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "event"  // sealed 判別を "event" フィールドで
    }
    override fun encode(event: WireEvent): ByteArray =
        json.encodeToString(WireEvent.serializer(), event).toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        json.decodeFromString(WireEvent.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}

class CapsCodec(private val capsFactory: CapsFactory) : Codec {
    // 実装は Phase 4
    // Caps のキー = event の SerialName、フィールド = sealed の各 case フィールド
}
```

### 2.6 双方向 parity test

**golden ファイル位置**:
```
:protocol/test/golden/kotlin/*.json   ← Kotlin が生成 (git に commit)
:protocol/test/golden/ts/*.json       ← TS が生成 (git に commit)
```

**テスト構造**:
- Kotlin side: `KotlinGoldenGenerator` で全 WireEvent case を JSON 化 → `kotlin/` に保存
- TS side (Phase 4 で実装): 同等 generator で `ts/` に保存
- Kotlin verifier: `ts/*.json` を JsonCodec で decode → 元の論理型と equal
- TS verifier: `kotlin/*.json` を TS 型で parse → 元の論理型と equal
- CI: 両方の verifier が green でない限り fail

---

## 3. Phone app 詳細設計

### 3.1 パッケージ構造

```
com.example.claudemobilehud.phone/
├── App.kt                    ← Application class
├── MainActivity.kt
├── data/
│   ├── ChannelRepository.kt
│   ├── ChannelClient.kt
│   ├── ConnectionController.kt
│   ├── InputController.kt
│   ├── SessionStore.kt
│   ├── HistoryStore.kt
│   ├── SettingsStore.kt
│   ├── ImageProcessor.kt
│   ├── PairingParser.kt
│   ├── QrScanner.kt
│   ├── error/
│   │   └── PhoneWireError.kt   ← Send / Transcription / Glass-specific (§3.7)
│   ├── model/
│   │   ├── ChatMessage.kt    ← image 添付込み永続型
│   │   ├── ImageAttachment.kt
│   │   ├── PendingPermission.kt
│   │   ├── PhoneUiState.kt
│   │   ├── ConnectivityState.kt
│   │   └── Settings.kt
│   └── transcription/
│       ├── TranscriptionClient.kt
│       ├── MicCapture.kt
│       ├── TranscriptionWs.kt
│       ├── EventCodec.kt
│       └── TranscriptionConfig.kt
├── service/
│   ├── AppLifecycleController.kt
│   ├── ChannelService.kt
│   ├── GlassConnectionService.kt
│   ├── MicForegroundService.kt
│   ├── VerdictDispatchService.kt
│   ├── NotificationFactory.kt
│   ├── PermissionActionReceiver.kt
│   └── TokenStore.kt
├── glass/
│   ├── GlassRelay.kt
│   ├── GlassEventDispatcher.kt
│   └── AudioRouter.kt
├── ui/
│   ├── ChatViewModel.kt
│   ├── MainScreen.kt
│   ├── MainScreenState.kt
│   ├── MainScreenScaffold.kt
│   ├── MainScreenDialogs.kt
│   ├── MainScreenEffects.kt
│   ├── components/...
│   ├── dialogs/...
│   ├── theme/...
│   └── util/...
└── log/
    └── StructuredLog.kt
```

### 3.2 データ層

#### 3.2.1 `ChannelRepository`

##### 3.2.1.1 公開 API

```kotlin
class ChannelRepository(private val applicationContext: Context) {
    // 構成要素
    private val settingsStore = SettingsStore(applicationContext)
    private val historyStore = HistoryStore(applicationContext)
    private val sessionStore = SessionStore(historyStore)
    private val connection = ConnectionController()
    private val input = InputController(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 公開 flow
    val uiState: StateFlow<PhoneUiState>
    val currentState: StateFlow<CurrentStatePayload>  // wire 用 (Glass push) + Phone UI atomicity
    val settings: StateFlow<Settings>
    val connectivity: StateFlow<ConnectivityState>    // 永続状態 (AuthFailed, NotConfigured 等)
    val events: SharedFlow<ChannelEvent>              // 一過性 (Reply / PermissionRequested)
    val errors: SharedFlow<TransientError>            // 一過性 (snackbar 系)

    // 公開 action
    fun saveSettings(s: Settings)
    fun reconnect()
    fun selectSession(sessionId: String)
    fun deleteSession(sessionId: String)
    fun updateInputText(text: String)
    fun clearInput()
    fun startTranscriptionPhoneMic()
    fun startTranscriptionFromGlass()
    fun stopTranscription()
    fun setConfirming(sessionId: String?, value: Boolean)
    fun sendCurrent()
    fun send(text: String)
    fun respondPermission(requestId: String, behavior: PermissionDecision)
    fun attachImage(image: ImageAttachment)
    fun clearAttachedImage()
    suspend fun flushHistory()  // shutdown 用 (§3.6.1)
}
```

##### 3.2.1.2 `currentState` の合成構造 (NFR-13 atomicity 達成、Rev 2 修正)

**重要修正**: Rev 1 では `combine { ... CurrentStatePayload(seq = nextSeq(), ...) ... }.stateIn(...)` と書いたが、これは `stateIn` の conflate / Eagerly 評価 / 再起動で `seq` が歪む。**Rev 2 では combine の外で 1 emit に 1 seq を採番する設計に変更**:

```kotlin
private val seqCounter = AtomicInteger(0)
private val _currentState = MutableStateFlow(initialCurrentStatePayload())
val currentState: StateFlow<CurrentStatePayload> = _currentState.asStateFlow()

init {
    scope.launch {
        combine(
            input.transcription.state,
            sessionStore.snapshot,
            _confirmingBySession,
            _pendingPermissions,
            input.text,
            input.micSource,
        ) { transState, sessionSnap, confirmingMap, pending, inputText, micSource ->
            // 純データの draft を組み立てる。seq は採番しない
            buildDraft(transState, sessionSnap, confirmingMap, pending, inputText, micSource)
        }
        .distinctUntilChanged()  // 同じ draft が連続したら dedup
        .collect { draft ->
            val newSeq = seqCounter.incrementAndGet()
            _currentState.value = draft.toPayload(seq = newSeq)
            StructuredLog.phoneStateEmit(_currentState.value)  // AC-09 検証用
        }
    }
}

private data class CurrentStateDraft(
    val mode: ConversationMode,
    val pendingPermission: PendingPermissionPayload?,
    val transcriptState: TranscriptState,
    val transcriptText: String,
    val inputText: String,
    val micSource: MicSource,
)
```

**保証**:
- `nextSeq` (= `seqCounter.incrementAndGet()`) は **collector 内で正確に 1 回**呼ばれる
- `distinctUntilChanged` で同じ内容は dedup されるため、無駄な seq 増加が起きない
- 初期値は `initialCurrentStatePayload()` (seq=0, mode=IDLE) で、最初の実 emit は seq=1
- combine の再評価 / coroutine restart が起きても、`seqCounter` は instance 変数で保持

##### 3.2.1.3 SSE イベント処理 (Hub 由来) + `permission_snapshot` 適用

```kotlin
private suspend fun onSseEvent(event: SseEvent) {
    sessionStore.applySseEvent(event)
    when (event) {
        is SseEvent.SessionSnapshot -> {
            sessionStore.reconcileActive(event.activeSessionIds)
        }
        is SseEvent.PermissionSnapshot -> {
            // AD-13: 受信 request_ids 順序を活用して rebuild (§1.3 D-低 反映)
            val authorityIds = event.requestIds.toSet()
            _pendingPermissions.update { current ->
                // 注意: 新規 List 参照を返すこと (distinctUntilChanged の罠回避)
                val kept = current.filter { it.requestId in authorityIds }
                // Hub 側 createdAtMs 順を尊重 (将来的に kept の order が ID 順と異なる場合は再ソート)
                kept.toList()
            }
        }
        is SseEvent.Reply -> {
            // ...
        }
        is SseEvent.Permission -> {
            _pendingPermissions.update { current ->
                if (current.any { it.requestId == event.requestId }) current
                else (current + event.toPending()).toList()  // 新規参照
            }
        }
        is SseEvent.PermissionAbort -> {
            _pendingPermissions.update { current ->
                val filtered = current.filterNot { it.requestId == event.requestId }
                if (filtered.size == current.size) current else filtered.toList()
            }
        }
        // SessionActive / SessionInactive / Closed / Failure ...
    }
}
```

##### 3.2.1.4 SharedFlow / StateFlow buffer policy (AD-14)

| flow | 種別 | replay | extraBufferCapacity | onBufferOverflow | 用途 |
|---|---|---|---|---|---|
| `uiState` | StateFlow | — | — | — | UI 表示状態 |
| `currentState` | StateFlow | — | — | — | wire push + atomicity |
| `settings` | StateFlow | — | — | — | 設定 |
| `connectivity` | StateFlow | — | — | — | 永続接続状態 (AuthFailed / NotConfigured / Open / Connecting / Failed) |
| `events` | SharedFlow | 0 | 16 | DROP_OLDEST | Reply / PermissionRequested の一過性通知 |
| `errors` | SharedFlow | 0 | 16 | DROP_OLDEST | 一過性エラー (snackbar 系)。永続状態は connectivity へ |

**重要**: AuthFailed は **永続状態**として `connectivity: StateFlow<ConnectivityState>` で持つ。UI の `LaunchedEffect(connectivity)` で collect すれば、購読タイミングに依らず必ず最新値が読める (replay 不要)。

#### 3.2.2 `ChannelClient` (Hub HTTP client)

```kotlin
class ChannelClient(private val baseUrl: String, private val token: String) {
    suspend fun send(text: String, sessionId: String?, image: ImageAttachment?): Result<SendResponse>
    suspend fun sendPermissionVerdict(requestId: String, decision: PermissionDecision): Result<Unit>
    fun events(): Flow<SseEvent>
}
```

##### 3.2.2.1 HTTP 401 ハンドリング (Rev 2 修正)

**POST 系 (`send` / `sendPermissionVerdict`)**:
```kotlin
private suspend fun exec(request: Request): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val response = httpClient.newCall(request).await()
        response.use { r ->
            val body = r.body.string()
            when {
                r.code == 401 -> throw SharedWireError.Connection.AuthFailed.toException()
                r.code == 410 -> throw SharedWireError.Permission.AlreadyVerdicted.toException()
                !r.isSuccessful -> throw SharedWireError.Connection.ServerError(r.code, body.take(200)).toException()
                else -> body
            }
        }
    }
}
```

**SSE (`events()`)**:
```kotlin
fun events(): Flow<SseEvent> = callbackFlow {
    val source = EventSources.createFactory(httpClient).newEventSource(
        request,
        object : EventSourceListener() {
            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                val event = when {
                    response?.code == 401 -> SseEvent.AuthFailed
                    else -> SseEvent.Failure(t?.message ?: response?.code?.toString() ?: "unknown")
                }
                trySend(event)
                close()
            }
            // ... other listeners
        },
    )
    awaitClose { source.cancel() }
}.flowOn(Dispatchers.IO)
```

`SseEvent.AuthFailed` を受けた ConnectionController は再試行ループを停止する (§3.2.4)。

#### 3.2.3 `SessionStore`

```kotlin
class SessionStore(private val historyStore: HistoryStore) {
    data class Snapshot(
        val sessions: List<SessionSummary>,
        val currentSessionId: String?,
        val messages: List<ChatMessage>,
    )
    val snapshot: StateFlow<Snapshot>

    suspend fun restoreFromHistory()
    fun selectSession(sessionId: String)
    fun deleteSession(sessionId: String)
    suspend fun appendMessage(...): ChatMessage
    suspend fun applySseEvent(event: SseEvent)
    suspend fun reconcileActive(activeIds: List<String>)
    suspend fun mergeUnknownSession(chatId: String, confirmedSessionId: String)
    suspend fun flush()  // shutdown 用 (§3.6.1)
}
```

`messagesBySession: MutableMap<String, MutableList<ChatMessage>>` を Mutex 保護下で操作する (POC 通り)。

#### 3.2.4 `ConnectionController` (exp backoff state machine、Rev 2 修正)

```kotlin
class ConnectionController {
    val status: StateFlow<ConnectivityState>
    val events: SharedFlow<SseEvent>
    val client: StateFlow<ChannelClient?>

    fun update(settings: Settings)
    fun reconnect()  // 手動トリガ (AuthFailed リセット用)
}
```

**state**:
```kotlin
sealed class ConnectivityState {
    data object Idle : ConnectivityState()           // NotConfigured 相当
    data object Connecting : ConnectivityState()
    data object Open : ConnectivityState()
    data class Failed(val reason: String) : ConnectivityState()    // 一時的失敗 (再試行中)
    data object AuthFailed : ConnectivityState()     // 永続失敗 (手動 reconnect が必要)
}
```

**backoff state machine (擬似コード)**:
```kotlin
private val reconnectTrigger = Channel<Unit>(Channel.CONFLATED)

private suspend fun runConnectionLoop(client: ChannelClient) {
    var attempt = 0
    while (currentCoroutineContext().isActive) {
        _status.value = ConnectivityState.Connecting
        var lastError: String? = null
        var authFailed = false
        try {
            client.events().collect { event ->
                when (event) {
                    SseEvent.Open -> {
                        attempt = 0
                        _status.value = ConnectivityState.Open
                    }
                    is SseEvent.Failure -> lastError = event.message
                    is SseEvent.AuthFailed -> { authFailed = true }
                    // ...
                }
                _events.emit(event)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Throwable) { lastError = e.message }

        if (authFailed) {
            _status.value = ConnectivityState.AuthFailed
            // 手動 reconnect() を待つ
            reconnectTrigger.receive()
            attempt = 0
            continue
        }

        if (!currentCoroutineContext().isActive) return

        attempt++
        val baseMs = (1000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
        val jitter = baseMs * (Random.nextDouble(-0.25, 0.25))
        val delayMs = (baseMs + jitter.toLong()).coerceAtLeast(100L)
        _status.value = ConnectivityState.Failed("${lastError} / 再接続待ち #$attempt")
        delay(delayMs)
    }
}

fun reconnect() {
    reconnectTrigger.trySend(Unit)
}
```

**重要規約**:
- AuthFailed では再試行ループを停止し、`reconnectTrigger` を待つ
- 手動 `reconnect()` は `attempt=0` にリセット + AuthFailed フラグクリア + ループ再開
- UI は `ConnectivityState.AuthFailed` を `LaunchedEffect(connectivity)` で検知 → SettingsDialog を自動表示

#### 3.2.5 `InputController` + `TranscriptionClient`

```kotlin
class InputController(private val applicationContext: Context) {
    val transcription = TranscriptionClient()
    val text: StateFlow<String>
    val micSource: StateFlow<MicSource>  // 録音時は GLASS / PHONE_FALLBACK

    fun setCurrentSession(id: String?)
    fun update(text: String)
    fun clear()
    fun startWithPhoneMic(apiKey: String)
    fun startFromGlass(apiKey: String)
    fun stop()
}
```

`startFromGlass` で `AudioRouter.routeToGlassMic` の戻り値を `micSource` に反映 (FR-GL-44 / §6.8)。

### 3.3 service 層

#### 3.3.1 `AppLifecycleController` (state machine 完全列挙、Rev 2 修正)

POC の負債 (FGS 間直接結合) を解消する中核。Rev 2 では `pendingRestart` 擬似変数を廃し、状態を `sealed class` で完全列挙する。

```kotlin
object AppLifecycleController {
    sealed class GlassFgsState {
        data object Off : GlassFgsState()
        data object Starting : GlassFgsState()
        data object Running : GlassFgsState()
        data class Stopping(val restartAfter: Boolean) : GlassFgsState()
    }

    enum class FgsKind { CHANNEL, GLASS_CONNECTION, MIC }
    enum class FgsLifecycle { ON_CREATE, ON_DESTROY }

    private val _channelState = MutableStateFlow(false)
    val channelRunning: StateFlow<Boolean> = _channelState.asStateFlow()

    private val _glassState = MutableStateFlow<GlassFgsState>(GlassFgsState.Off)
    val glassState: StateFlow<GlassFgsState> = _glassState.asStateFlow()

    // FGS の実 alive 状態を track
    private var glassFgsRunning = false
    private var micFgsRunning = false

    fun startChannel(context: Context)
    fun stopChannel(context: Context)
    fun startGlassSession(context: Context)
    fun stopGlassSession(context: Context)
    fun shutdownAll(context: Context)

    internal fun onGlassDisconnected(context: Context)
    internal fun onFgsLifecycle(fgs: FgsKind, event: FgsLifecycle, context: Context)
}
```

**Glass+Mic FGS の遷移表**:

| 現状態 | トリガ | 次状態 | 副作用 |
|---|---|---|---|
| `Off` | `startGlassSession` | `Starting` | GlassFGS + MicFGS の startForegroundService 発火 |
| `Off` | `stopGlassSession` | `Off` | no-op |
| `Off` | `onGlassDisconnected` | `Off` | no-op |
| `Starting` | `startGlassSession` | `Starting` | no-op (冪等) |
| `Starting` | `stopGlassSession` | `Stopping(restartAfter=false)` | 両 FGS の stopService 発火 |
| `Starting` | `onFgsLifecycle(both ON_CREATE)` | `Running` | — |
| `Starting` | `onGlassDisconnected` | `Stopping(restartAfter=false)` | 両 FGS の stopService 発火 |
| `Running` | `startGlassSession` | `Running` | no-op (冪等) |
| `Running` | `stopGlassSession` | `Stopping(restartAfter=false)` | 両 FGS の stopService 発火 |
| `Running` | `onGlassDisconnected` | `Stopping(restartAfter=false)` | 両 FGS の stopService 発火 |
| `Stopping(_)` | `startGlassSession` | `Stopping(restartAfter=true)` | 待機 (start 予約) |
| `Stopping(_)` | `stopGlassSession` | `Stopping(restartAfter=false)` | restartAfter 取消 |
| `Stopping(_)` | `onGlassDisconnected` | `Stopping(_)` | no-op (既に stopping) |
| `Stopping(false)` | `onFgsLifecycle(both ON_DESTROY)` | `Off` | — |
| `Stopping(true)` | `onFgsLifecycle(both ON_DESTROY)` | `Starting` | GlassFGS + MicFGS の startForegroundService を**再発火** |

**`shutdownAll`**:
- `stopGlassSession(context)` → 状態が Stopping に遷移
- `stopChannel(context)` → ChannelService 停止
- すべて `Off` まで完了したら return (suspend で待つ実装は Phase 4)

**`onFgsLifecycle`** は GlassConnectionService.onCreate/onDestroy と MicForegroundService.onCreate/onDestroy から呼ばれる。

**片側 lifecycle 通知の扱い** (Rev 3 補強):

Glass FGS と Mic FGS は独立 FGS のため、`onCreate` / `onDestroy` の到着順は非同期。controller 内部で `glassFgsRunning` / `micFgsRunning` の 2 boolean を保持し、**両方揃った時点でのみ state 遷移**する:

| 受信通知 | 内部 boolean 更新 | state 遷移 |
|---|---|---|
| Glass `ON_CREATE` のみ | `glassFgsRunning = true` | 不変 |
| Mic `ON_CREATE` のみ | `micFgsRunning = true` | 不変 |
| Glass + Mic 両方 ON_CREATE | both true | `Starting → Running` |
| Glass `ON_DESTROY` のみ | `glassFgsRunning = false` | 不変 |
| Mic `ON_DESTROY` のみ | `micFgsRunning = false` | 不変 |
| Glass + Mic 両方 ON_DESTROY | both false | `Stopping(false) → Off` または `Stopping(true) → Starting` (+ 両 FGS 再起動) |

**`Stopping(true)` 最中の外部 stop**:
- `Stopping(restartAfter=true)` 中に `stopGlassSession` が呼ばれた → 状態を `Stopping(restartAfter=false)` に**降格** (restart 予約を取消)
- これにより「停止 → 再起動予約 → 取消し」の流れが線形に表現される

**異常系**:
- 片方 FGS のみ起動成功・片方失敗 (例: Mic だけ ON_CREATE、Glass は startForegroundService 失敗) → タイムアウト機構が必要。Phase 4 で `Starting` 状態に 5 秒タイマーを付与し、5 秒以内に Running に到達しなければ Stopping(false) へ強制遷移する実装方針を確定 (本書 §10.2 に追記)

#### 3.3.2 `ChannelService`

`AppLifecycleController.startChannel` から起動される FGS。役割:
- `ChannelRepository.connectivity` を購読し、通知文言を更新
- `ChannelRepository.events` を購読し、reply / permission 通知を post
- POC との差分: **MicForegroundService を直接 start/stop しない**

#### 3.3.3 `GlassConnectionService`

`AppLifecycleController.startGlassSession` から起動される FGS。役割:
- CXR-L 接続管理 (POC と同様)
- 自然切断検出時に **`AppLifecycleController.onGlassDisconnected(context)` を呼ぶ** (Mic FGS を直接知らない)
- onCreate / onDestroy で **`AppLifecycleController.onFgsLifecycle(GLASS_CONNECTION, ...)` を呼ぶ**
- 受信メッセージは `GlassEventDispatcher` (Repository scope) に流す

#### 3.3.4 `MicForegroundService`

`AppLifecycleController` 経由でのみ叩かれる。companion からは start/stop は提供せず、`AppLifecycleController.startGlassSession` のみがトリガ。onCreate / onDestroy で `onFgsLifecycle(MIC, ...)` を呼ぶ。

#### 3.3.5 `VerdictDispatchService` (FGS 化、Rev 2 修正、AD-16)

**変更点**: Rev 1 では `Service` で OK としていたが、レビュアー指摘の通り Android 14+ background service start 制限を回避するため **FGS-dataSync 型に格上げ**。短命 (3 秒以内に stopSelf)。

```kotlin
class VerdictDispatchService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationFactory.verdictDispatch(this, "Verdict を送信中")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_VERDICT_DISPATCH, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_VERDICT_DISPATCH, notification)
        }

        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return stopWith(START_NOT_STICKY)
        val behaviorStr = intent.getStringExtra(EXTRA_BEHAVIOR) ?: return stopWith(START_NOT_STICKY)
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: return stopWith(START_NOT_STICKY)
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return stopWith(START_NOT_STICKY)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        CoroutineScope(Dispatchers.IO).launch {
            val client = ChannelClient(baseUrl, token)
            val decision = PermissionDecision.valueOf(behaviorStr)
            val result = client.sendPermissionVerdict(requestId, decision)
            when {
                result.isSuccess -> {
                    if (notificationId >= 0) {
                        getSystemService(NotificationManager::class.java)?.cancel(notificationId)
                    }
                }
                result.exceptionOrNull() is SharedWireError.Connection.AuthFailed.Exception -> {
                    // 通知文言を「再ペアが必要」に書き換え (§6.7.1)
                    updateNotificationForAuthFail(this@VerdictDispatchService, notificationId)
                }
                else -> {
                    StructuredLog.error("verdict_dispatch_failed", result.exceptionOrNull())
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    // ...
}
```

##### 3.3.5.1 NFR-14 (5s) 予算表

| 工程 | 想定時間 (中央値) | 累計 |
|---|---|---|
| BroadcastReceiver.onReceive → startForegroundService | 50ms | 50ms |
| Service プロセス起動 (cold start, Application.onCreate 含む) | 600ms | 650ms |
| onStartCommand → startForeground 通知表示 | 100ms | 750ms |
| Intent extras parsing | 5ms | 755ms |
| DataStore 読み出し (不要、extras 経由) | 0ms | 755ms |
| ChannelClient new (OkHttpClient builder) | 300ms | 1055ms |
| DNS resolve | 100ms | 1155ms |
| TCP connect | 100ms | 1255ms |
| POST /permission round-trip (LAN/Tailscale) | 200ms | 1455ms |
| stopForeground + stopSelf | 50ms | 1505ms |

**合計 中央値 ~1.5s**。p99 で予算 3s 程度。NFR-14 (5s) は十分達成可能。

**冷起動最悪ケース**:
- アプリプロセス完全死後の初回 = アプリの cold start 全コスト (~2s) を加算しても 3.5s 程度
- LAN/Tailscale 不通 = OkHttp の timeout が deadlock するので、`callTimeout = 4s` を ChannelClient で明示設定 (NFR-14 内に収める)

##### 3.3.5.2 PendingIntent extras

```
EXTRA_REQUEST_ID: String     ← request_id
EXTRA_BEHAVIOR: String        ← "ALLOW" or "DENY"
EXTRA_BASE_URL: String        ← Hub の baseUrl (DataStore 冗長化)
EXTRA_TOKEN: String           ← X-Token (DataStore 冗長化)
EXTRA_NOTIFICATION_ID: Int    ← 通知 ID (verdict 成功時 cancel 用)
```

PendingIntent は `FLAG_IMMUTABLE` 必須。

##### 3.3.5.3 FGS-dataSync の同時稼働 (Rev 3 追記)

本プロジェクトで `FOREGROUND_SERVICE_TYPE_DATA_SYNC` を使う FGS は以下:
- `ChannelService` (常駐、Hub 接続維持)
- `GlassConnectionService` (Glass 接続中のみ)
- `VerdictDispatchService` (kill 中 verdict 送出時のみ、3 秒以内に stopSelf)

**同時稼働パターン**:
- 通常前景時: `ChannelService` のみ (1 個)
- Glass 接続時: `ChannelService` + `GlassConnectionService` (+ Mic FGS の type-microphone) = dataSync 2 個
- Kill 中 verdict 時: `ChannelService` のみ (kill 状態なので Glass は接続切れている) + `VerdictDispatchService` = dataSync 2 個

**dataSync FGS が 3 個以上同時稼働するパターンは存在しない**。

**Manifest 宣言** (Phase 4 で実装):
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<!-- 3 つの FGS が共有。Play Store 審査用に「Hub との通信維持 + Glass との通信維持 + permission verdict 送出」と用途明示 -->
```

### 3.4 glass relay 層

#### 3.4.1 `GlassRelay` + NFR-13 atomicity 射程 (AD-15)

**Rev 1 では「7 個 observer を currentState 1 本で大半被覆」と書いたが、これは厳密ではない**:
- `currentState` には `mode + 関連 payload` (pending / transcript / input / mic_source) のみ
- `session_list` / `current_session` / `messages` / `notification` は別 wire

**AD-15: NFR-13 atomicity 射程を「currentState (mode + 関連 payload)」に限定**:
- mode と pending / transcript の乖離 → 禁止 (current_state 1 wire で保証)
- mode と session_list の乖離 → 許容 (eventual consistency)
- mode と messages の乖離 → 許容

**根拠**:
- NFR-13 の本来の意図は「ユーザに見える 1 フレームで mode と payload (pending 等) が乖離しない」
- session_list / messages は別 UI 要素 (ドロワー / 履歴ビュー) で、現 mode と直接的な依存はない
- AC-09 自動検証 (§7.2) も `glass_state_swap` / `phone_state_emit` (= currentState 由来) のみ検査対象

**GlassRelay 実装**:

```kotlin
class GlassRelay(
    private val repository: ChannelRepository,
    private val codec: CapsCodec,
) {
    fun start() { /* observer 起動 */ }
    fun refresh() { /* Glass hello で trigger */ }
    fun stop()
}

private suspend fun observeAll(sender: (ByteArray) -> Unit) = coroutineScope {
    // atomicity 対象 (current_state 1 本に集約)
    launch { repository.currentState.collect { send(sender, codec.encode(it.toWireEvent())) } }

    // eventual consistency (各々独立)
    launch { repository.uiState.map { it.sessions }.distinctUntilChanged().collect { sendSessionList(sender, it) } }
    launch { repository.uiState.map { it.currentSessionId }.distinctUntilChanged().collect { sendCurrentSession(sender, it) } }
    launch { repository.uiState.map { it.currentSessionId to it.messages }.distinctUntilChanged().collect { sendMessages(sender, it) } }
    launch { observeNotifications(sender) }
}
```

#### 3.4.2 `GlassEventDispatcher` (wire → Repository)

```kotlin
class GlassEventDispatcher(
    private val repository: ChannelRepository,
    private val relay: GlassRelay,
    private val codec: CapsCodec,
) {
    private suspend fun handleWireEvent(event: WireEvent) = when (event) {
        is Hello -> relay.refresh()
        is SelectSession -> repository.selectSession(event.id)
        is GestureEvent -> handleGesture(event.which)
        is ListeningCancel -> {
            repository.stopTranscription()
            repository.clearInput()
            repository.setConfirming(null, false)
        }
        is PermissionVerdictEvent -> repository.respondPermission(event.requestId, event.decision)
        else -> { /* Phone 向けイベントが Glass 経由で来ることはない */ }
    }
}
```

### 3.5 UI 層

#### 3.5.1 `MainScreen` 分解 (P1-6 対応)

POC の 275 行 1 composable を **3 + state holder 1** に分解:

```kotlin
@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val dialogState = rememberMainScreenDialogState(connectivity)

    MainScreenScaffold(ui, settings, dialogState, viewModel)
    MainScreenDialogs(ui, settings, dialogState, viewModel)
    MainScreenEffects(ui, connectivity, viewModel, dialogState.snackbar)
}
```

各 composable の責務:
- `MainScreenScaffold`: TopBar + Drawer + InputBar + MessageList
- `MainScreenDialogs`: 4 dialog の if 分岐 (settings/glass/exit/deletion + permission)
- `MainScreenEffects`: LaunchedEffect 群 (snackbar / dialog 自動表示)
- `MainScreenDialogState`: ダイアログ open/close + snackbar 状態

#### 3.5.2 `ChatViewModel`

```kotlin
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppContainer.repository
    val uiState: StateFlow<PhoneUiState> = repository.uiState
    val settings: StateFlow<Settings> = repository.settings
    val connectivity: StateFlow<ConnectivityState> = repository.connectivity
    // ...
}
```

#### 3.5.3 Compose recomposition 戦略 (AD-18)

**ガイドライン**:

| 戦略 | 適用ケース |
|---|---|
| `collectAsStateWithLifecycle` | 全 StateFlow / SharedFlow の収集 (lifecycle 連動でリーク防止) |
| 大きな data class の直接 collect | `PhoneUiState` のように複数 composable で共有する state |
| `derivedStateOf { ... }` で抽出 | UI の特定箇所のみ依存する派生値 (例: `MessageList` 用の `messages` だけ) |
| `@Stable` annotation | data class が "等価判定でスキップ可能" なことを Compose に通知 |
| `key()` ブロック | LazyColumn の items に必須 (`key = { it.id }`) |

**Phase 4 実装規約 (具体例)**:
- `MainScreenScaffold` 内で `val messages = remember(ui) { derivedStateOf { ui.messages } }` のように抽出
- `PhoneUiState` / `ConnectivityState` / `Settings` には `@Stable` を付与
- `MessageList` は `messages: List<ChatMessage>` を受け取り、parent の他フィールド変化で recomposition されない

### 3.6 永続化

#### 3.6.1 履歴 atomic write (AD-17)

**判断**: **JSON 継続** (POC 通り)。理由:
- v1.0 規模 (200 MB cap) では JSON で性能十分
- sqlite 移行は schema migration / Room 依存追加で複雑化

**atomic write** (Rev 3 修正 — `Files.move(ATOMIC_MOVE)` を使用してアトミック性を保証):

```kotlin
suspend fun save(snapshot: Map<String, List<ChatMessage>>) = mutex.withLock {
    withContext(Dispatchers.IO) {
        val tmp = File(filesDir, "chat-history.json.tmp")
        val target = File(filesDir, "chat-history.json")
        tmp.writeText(json.encodeToString(snapshot))
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // 異 filesystem 跨ぎ等で ATOMIC_MOVE が失敗した場合は .tmp を残し、
            // エラーログのみ。次回起動時に load() の冒頭で .tmp を検出して復旧 (下記)
            StructuredLog.error("history_atomic_move_failed", e)
            // tmp は削除しない (= 次回 load で復旧チャンス)
        }
    }
}
```

**起動時の `.tmp` 復旧**: `load()` の冒頭で `chat-history.json.tmp` が存在し、本体が存在しないか古い場合 → tmp を target にリネーム試行。本体が新しければ tmp は破棄。

**Android API レベル要件**: `Files.move(ATOMIC_MOVE)` は Android 26+ (`minSdk 31` を満たす)。

**読み込み時の壊れた JSON 検出**:
```kotlin
suspend fun load(): MutableMap<String, MutableList<ChatMessage>> = mutex.withLock {
    withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext mutableMapOf()
        runCatching {
            json.decodeFromString(serializer, file.readText())
                .mapValues { it.value.toMutableList() }.toMutableMap()
        }.recoverCatching { e ->
            StructuredLog.error("history_corrupt", e)
            // 旧ファイルをバックアップ + 空で再開
            file.renameTo(File(filesDir, "chat-history.json.corrupt.${System.currentTimeMillis()}"))
            mutableMapOf<String, MutableList<ChatMessage>>()
        }.getOrDefault(mutableMapOf())
    }
}
```

**shutdown flush**:
- `AppLifecycleController.shutdownAll(context)` 内で `repository.flushHistory()` を呼ぶ
- 500ms debounce job をキャンセルして即時 save 実行
- ExitDialog の確定ボタンも `shutdownAll → Activity.finishAndRemoveTask()` の順で呼ぶ

#### 3.6.2 設定 DataStore

```
Preferences keys:
- "base_url" (String)
- "token" (String)
- "openai_api_key" (String)
- "last_current_session_id" (String, FR-PH-54)
```

#### 3.6.3 通知 channel id

```kotlin
object NotificationChannels {
    const val SERVICE = "claude-mhud-service-v1"
    const val MIC = "claude-mhud-mic-v1"
    const val REPLY = "claude-mhud-reply-v1"
    const val PERMISSION = "claude-mhud-permission-v1"
    const val GLASS_CONNECTION = "claude-mhud-cxr-v1"
    const val VERDICT_DISPATCH = "claude-mhud-verdict-v1"
}
```

**§1.3: v1.0 期間中は変更不可**。v2.0 で変更する場合はマイグレーション設計が必要。

#### 3.6.4 通知タップ extras schema

```
PendingIntent extras (FR-PH-33):
- "session_id": String      ← 該当 session
- "notification_kind": String  ← "reply" | "permission"
- "request_id": String (optional)  ← permission のとき
```

`MainActivity.onNewIntent` で extras を読み、`viewModel.selectSession(sessionId)` を呼んで該当 session の会話画面に切替。

### 3.7 `PhoneWireError` 階層

`:protocol` の `SharedWireError` (Connection / Permission) を継承して Phone 固有エラーを追加:

```kotlin
sealed class PhoneWireError {
    // SharedWireError から継承 (data 層では SharedWireError を直接使う)

    sealed class Send {
        data class ImageTooLarge(val actualBytes: Long, val limitBytes: Long) : Send()
        data class SessionNotActive(val sessionId: String) : Send()
        data object Cancelled : Send()
    }
    sealed class Transcription {
        data object ApiKeyMissing : Transcription()
        data object ApiKeyInvalid : Transcription()
        data object MicPermissionDenied : Transcription()
        data class NetworkFailed(val causeMessage: String?) : Transcription()
        data class ServiceError(val message: String) : Transcription()
    }
    sealed class Glass {
        data object TokenMissing : Glass()
        data object HiRokidNotInstalled : Glass()
        data class CxrConnectFailed(val causeMessage: String?) : Glass()
        data object BtScoUnavailable : Glass()
    }
}
```

**UI 表現マッピング** (Phase 4 実装):

```kotlin
sealed class UiPresentation {
    data class Snackbar(val message: String) : UiPresentation()
    data class Dialog(val title: String, val body: String, val action: (() -> Unit)?) : UiPresentation()
    data class Banner(val message: String) : UiPresentation()
}

fun mapToPresentation(error: Any): UiPresentation = when (error) {
    is SharedWireError.Connection.NotConfigured -> UiPresentation.Banner("先に設定を開いてください")
    is SharedWireError.Connection.AuthFailed -> UiPresentation.Dialog(
        title = "token が無効です",
        body = "Hub の token が変わった可能性があります。QR を再スキャンしてください。",
        action = { /* open SettingsDialog */ }
    )
    is SharedWireError.Permission.Aborted -> UiPresentation.Snackbar("Claude 側で取消されました")
    is PhoneWireError.Send.ImageTooLarge -> UiPresentation.Snackbar("画像が大きすぎます")
    is PhoneWireError.Transcription.ApiKeyMissing -> UiPresentation.Snackbar("OpenAI API key が未設定")
    is PhoneWireError.Glass.BtScoUnavailable -> UiPresentation.Banner("内蔵マイクを使用中")
    // ...
}
```

### 3.8 通知シェード経由 verdict 経路の Receiver

```kotlin
class PermissionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val behavior = if (intent.getBooleanExtra(EXTRA_ALLOW, false)) "ALLOW" else "DENY"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        // アプリプロセス生存時は Repository 経由
        if (AppContainer.isInitialized) {
            AppContainer.repository.respondPermission(requestId, PermissionDecision.valueOf(behavior))
            context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        } else {
            // kill 状態: VerdictDispatchService に委譲
            val baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: return
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
            val serviceIntent = Intent(context, VerdictDispatchService::class.java).apply {
                putExtra(VerdictDispatchService.EXTRA_REQUEST_ID, requestId)
                putExtra(VerdictDispatchService.EXTRA_BEHAVIOR, behavior)
                putExtra(VerdictDispatchService.EXTRA_BASE_URL, baseUrl)
                putExtra(VerdictDispatchService.EXTRA_TOKEN, token)
                putExtra(VerdictDispatchService.EXTRA_NOTIFICATION_ID, notificationId)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
```

---

## 4. Glass app 詳細設計

### 4.1 パッケージ構造

```
com.example.claudemobilehud.glass/
├── App.kt
├── MainActivity.kt
├── GlassBridge.kt              ← CXR endpoint (object)
├── ScreenAwakeManager.kt
├── NotificationSound.kt
├── gesture/
│   └── GlassGesture.kt
├── ui/
│   ├── ConnectionGate.kt
│   ├── SessionNavigator.kt
│   ├── nav/GlassNav.kt
│   ├── sessionselect/SessionSelectScreen.kt
│   ├── conversation/
│   │   ├── ConversationScreen.kt
│   │   ├── ConversationStateHolder.kt
│   │   └── ChatFrame.kt
│   └── theme/
└── log/
    └── StructuredLog.kt        ← glass_state_swap helper (AD-19)
```

`:protocol` を `implementation(project(":protocol"))` で参照。

### 4.2 `GlassBridge` (CXR endpoint)

```kotlin
object GlassBridge {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    val status: StateFlow<Status>
    val sessionOpen: StateFlow<Boolean>
    val phoneState: StateFlow<PhoneState>           // §4.3 で詳述
    val sessions: StateFlow<List<SessionSummary>>
    val currentSessionId: StateFlow<String?>
    val messages: StateFlow<List<ChatMessage>>
    val notifications: SharedFlow<NotificationInfo>  // replay=0, buffer=8, DROP_OLDEST

    fun init(context: Context)
    fun sendHello()
    fun sendGesture(which: GestureKind)
    fun sendListeningCancel()
    fun sendSelectSession(id: String)
    fun sendPermissionVerdict(requestId: String, decision: PermissionDecision)
}
```

#### 4.2.1 受信時の atomicity 実装

```kotlin
private var lastSeenStateSeq: Int = 0
private var pendingInputText: Pair<Int, String>? = null

private fun onWireRecv(event: WireEvent) = when (event) {
    is SessionOpen -> {
        // Phase 2 §4.5.3 B-1 対応: Phone 再起動を吸収
        lastSeenStateSeq = 0
        pendingInputText = null
        _sessionOpen.value = true
    }
    is CurrentState -> {
        if (event.seq <= lastSeenStateSeq) return  // stale ドロップ
        val effectiveInputText = pendingInputText?.let {
            if (it.first == event.seq) it.second else null
        } ?: event.inputText
        _phoneState.value = PhoneState(
            mode = event.mode,
            pendingPermission = event.pendingPermission?.toGlassModel(),
            transcriptState = event.transcriptState,
            transcriptText = event.transcriptText,
            inputText = effectiveInputText,
            micSource = event.micSource,
        )
        lastSeenStateSeq = event.seq
        if (pendingInputText?.first == event.seq) pendingInputText = null
        StructuredLog.glassStateSwap(
            seq = event.seq, mode = event.mode,
            pendingRequestId = event.pendingPermission?.requestId,
            transcriptState = event.transcriptState,
            inputLen = effectiveInputText.length,
            micSource = event.micSource,
        )
    }
    is InputTextOnly -> {
        when {
            event.parentSeq < lastSeenStateSeq -> Unit
            event.parentSeq > lastSeenStateSeq -> {
                pendingInputText = event.parentSeq to event.inputText
            }
            else -> {
                _phoneState.value = _phoneState.value.copy(inputText = event.inputText)
                StructuredLog.glassStateSwap(
                    seq = lastSeenStateSeq, mode = _phoneState.value.mode,
                    pendingRequestId = _phoneState.value.pendingPermission?.requestId,
                    transcriptState = _phoneState.value.transcriptState,
                    inputLen = event.inputText.length,
                    micSource = _phoneState.value.micSource,
                )
            }
        }
    }
    // ...
}
```

### 4.3 `PhoneState` (Glass-local model)

```kotlin
data class PhoneState(
    val mode: ConversationMode,
    val pendingPermission: GlassPendingPermission?,
    val transcriptState: TranscriptState,
    val transcriptText: String,
    val inputText: String,
    val micSource: MicSource,
)
```

immutable な data class。`GlassBridge` 内の `_phoneState: MutableStateFlow<PhoneState>` を**新規インスタンスでのみ swap**。

### 4.4 `ConversationStateHolder`

POC の構造を維持しつつ `PhoneState` を 1 つの上流とする。詳細 (sealed State / SendChoice / PermissionChoice) は Rev 1 通り。

### 4.5 構造化ログ

```kotlin
object StructuredLog {
    fun glassStateSwap(
        seq: Int, mode: ConversationMode, pendingRequestId: String?,
        transcriptState: TranscriptState, inputLen: Int, micSource: MicSource,
    ) {
        Log.i("channel.glass", "event=glass_state_swap " +
            "seq=$seq mode=${mode.name} " +
            "pending_request_id=${pendingRequestId ?: "null"} " +
            "transcript_state=${transcriptState.name} " +
            "input_len=$inputLen mic_source=${micSource.name}")
    }
    // wireSend / wireRecv / error 等は §8 規約に従う
}
```

---

## 5. Hub 詳細設計 (TypeScript)

### 5.1 module 構造

```
hub/
├── package.json
├── tsconfig.json
└── src/
    ├── index.ts                ← entry (CLI 起動)
    ├── server/
    │   ├── HttpServer.ts       ← Phone 向け HTTP/SSE (port 8788)
    │   └── BridgeServer.ts     ← Bridge 向け NDJSON (port 8787, loopback)
    ├── state/
    │   ├── SessionRegistry.ts
    │   ├── ChatRegistry.ts
    │   └── OutstandingPermissions.ts
    ├── wire/
    │   ├── PhoneWire.ts        ← Phone↔Hub JSON 型
    │   ├── BridgeWire.ts       ← Hub↔Bridge NDJSON 型
    │   └── ErrorCodes.ts       ← HTTP error_code 定義 (AD-21)
    ├── config/
    │   └── Config.ts
    └── log/
        └── StructuredLog.ts
```

### 5.2 主要モジュール

#### 5.2.1 HTTP error_code 体系 (AD-21)

POST レスポンスの 4xx で返す JSON body:

```typescript
type ErrorResponse = {
    error_code: string;
    message: string;
};

// Hub から返す error_code 一覧
const ERROR_CODES = {
    AUTH_FAILED: 'auth_failed',              // 401: token mismatch
    PERMISSION_GONE: 'permission_gone',      // 410: verdict already sent or request unknown
    IMAGE_TOO_LARGE: 'image_too_large',      // 400: image size > limit
    SESSION_NOT_ACTIVE: 'session_not_active', // 400: session_id specified but inactive
    INVALID_PAYLOAD: 'invalid_payload',      // 400: malformed JSON / missing fields
    INTERNAL_ERROR: 'internal_error',         // 500: unhandled exception
} as const;
```

**Phone 側の error_code → SharedWireError マッピング**:
- `auth_failed` (401) → `SharedWireError.Connection.AuthFailed`
- `permission_gone` (410) → `SharedWireError.Permission.AlreadyVerdicted`
- `image_too_large` (400) → `PhoneWireError.Send.ImageTooLarge`
- 他 → `SharedWireError.Connection.ServerError(httpCode, body)`

#### 5.2.2 `HttpServer`

- POST `/send`, POST `/permission`, GET `/events` (SSE) を提供
- X-Token 検証 (NFR-20)
- 接続切れの検出 + 自動再接続耐性

#### 5.2.3 `BridgeServer` + `OutstandingPermissions`

```typescript
interface OutstandingEntry {
    requestId: string;
    sessionId: string | null;
    toolName: string;
    description: string;
    inputPreview: string;
    createdAtMs: number;
    bridgeSessionId: string;  // どの Bridge 由来か (FR-HU-13 の自動 abort 用)
}

class OutstandingPermissions {
    private entries = new Map<string, OutstandingEntry>();

    add(entry: OutstandingEntry): void {
        this.entries.set(entry.requestId, entry);
    }

    remove(requestId: string): OutstandingEntry | undefined {
        const entry = this.entries.get(requestId);
        this.entries.delete(requestId);
        return entry;
    }

    /** FR-HU-13: Bridge 切断時、当該 outstanding 全てに abort を Phone へ自動 push */
    onBridgeDisconnected(bridgeSessionId: string, pushToPhone: (event: PermissionAbortSse) => void): void {
        for (const [rid, entry] of this.entries) {
            if (entry.bridgeSessionId === bridgeSessionId) {
                pushToPhone({ type: 'permission_abort', request_id: rid });
                this.entries.delete(rid);
            }
        }
    }

    /** AD-13 / FR-HU-14: SSE 再接続時の snapshot 送出 (createdAtMs 昇順) */
    buildSnapshot(): { requestIds: string[]; entries: OutstandingEntry[] } {
        const sorted = Array.from(this.entries.values()).sort((a, b) => a.createdAtMs - b.createdAtMs);
        return {
            requestIds: sorted.map(e => e.requestId),
            entries: sorted,
        };
    }
}
```

#### 5.2.4 SSE 再接続時の push シーケンス

```typescript
async function onSseConnected(socket: SseSocket) {
    // 1. session_snapshot (FR-HU-05)
    socket.send({ type: 'session_snapshot', active_session_ids: sessionRegistry.activeIds() });

    // 2. permission_snapshot (AD-13 / FR-HU-14)
    const snapshot = outstandingPermissions.buildSnapshot();
    socket.send({ type: 'permission_snapshot', request_ids: snapshot.requestIds });

    // 3. 個別 permission の再 push (createdAtMs 昇順)
    for (const entry of snapshot.entries) {
        socket.send({
            type: 'permission',
            request_id: entry.requestId,
            session_id: entry.sessionId,
            tool_name: entry.toolName,
            description: entry.description,
            input_preview: entry.inputPreview,
        });
    }

    // 4. 以降のリアルタイム push (通常運用)
}
```

### 5.3 Hub 再起動の振る舞い (AD-13)

Hub プロセス起動直後:
- `outstandingPermissions` = 空
- `sessionRegistry` = 空 (Bridge 再接続を待つ)
- Phone SSE 接続待ち

Phone が SSE で再接続:
1. `session_snapshot { active_session_ids: [] }` を送出 (空、Bridge 未登録)
2. `permission_snapshot { request_ids: [] }` を送出 (outstanding ゼロ)
3. Phone 側で local pending = {} ∩ {} = {} にリセット (AD-13)

Bridge が順次再接続して register してきたら通常運用に戻る。

### 5.4 wire 型 (Phone↔Hub JSON)

```typescript
// hub/src/wire/PhoneWire.ts

export type ReplySse = { type: 'reply'; chat_id: string; session_id?: string; text: string; };
export type PermissionSse = { type: 'permission'; request_id: string; session_id?: string; tool_name: string; description: string; input_preview: string; };
export type PermissionAbortSse = { type: 'permission_abort'; request_id: string; reason?: string; };
export type SessionSnapshotSse = { type: 'session_snapshot'; active_session_ids: string[]; };
export type PermissionSnapshotSse = { type: 'permission_snapshot'; request_ids: string[]; };
export type SessionActiveSse = { type: 'session_active'; session_id: string; };
export type SessionInactiveSse = { type: 'session_inactive'; session_id: string; };

export type PhoneSseEvent =
    | ReplySse | PermissionSse | PermissionAbortSse
    | SessionSnapshotSse | PermissionSnapshotSse
    | SessionActiveSse | SessionInactiveSse;
```

---

## 6. Bridge 詳細設計 (TypeScript)

### 6.1 module 構造

```
bridge/
├── package.json
├── src/
│   ├── index.ts
│   ├── McpServer.ts
│   ├── HubClient.ts
│   ├── SessionDetector.ts
│   ├── ImageStaging.ts
│   ├── wire/HubWire.ts
│   └── log/StructuredLog.ts
```

### 6.2 主要モジュール

#### 6.2.1 `McpServer`

- Claude Code から stdio で MCP プロトコルを受ける
- `reply` tool を提供
- permission notification を受信、Hub に転送
- channel message を受信、Hub に転送

#### 6.2.2 `HubClient` (register / ack 待ち、§1.3 D-中 対応)

```typescript
class HubClient {
    private socket: net.Socket | null = null;
    private registered = false;
    private outgoingQueue: BridgeWireMessage[] = [];

    async connect(host: string, port: number, sessionId: string): Promise<void> {
        this.socket = net.connect(port, host);
        this.socket.on('data', this.onData.bind(this));
        this.socket.on('close', this.onClose.bind(this));
        await this.send({ type: 'register', session_id: sessionId, pid: process.pid });
        // ack を待つまで他のメッセージは queue
    }

    async sendReply(chatId: string, sessionId: string, text: string): Promise<void> {
        const msg: ReplyMessage = { type: 'reply', chat_id: chatId, session_id: sessionId, text };
        if (this.registered) {
            await this.send(msg);
        } else {
            this.outgoingQueue.push(msg);
        }
    }

    private onData(buf: Buffer): void {
        // NDJSON parse
        for (const line of buf.toString().split('\n')) {
            if (!line.trim()) continue;
            const msg = JSON.parse(line);
            if (msg.type === 'ack_register') {
                this.registered = true;
                // queue flush
                for (const queued of this.outgoingQueue) {
                    this.send(queued);
                }
                this.outgoingQueue = [];
            }
            // ...
        }
    }
}
```

#### 6.2.3 `SessionDetector`

POC と同じく `/proc/<ppid>/cmdline` から `--session-id` を抜く。

#### 6.2.4 `ImageStaging`

Phone から base64 画像を受け取ったら `~/.claude/channels/mobile-hud/inbox/<uuid>.jpg` に書き出し、Claude に `meta.image_path` で渡す (Bridge 終了で削除)。

### 6.3 Bridge 終了時の挙動

- Claude が die → Bridge も die (stdio close)
- Hub 側 `BridgeServer` が socket close 検出 → `OutstandingPermissions.onBridgeDisconnected(bridgeSessionId)` 発火
- Phone に `permission_abort` が自動 push (FR-HU-13)

---

## 7. テストランナー詳細

### 7.1 単体テスト ハーネス

| 層 | フレームワーク | 補助 |
|---|---|---|
| Kotlin pure functions | JUnit 5 | — |
| Kotlin Flow / Coroutines | JUnit 5 | Turbine |
| TypeScript | Vitest | — |

カバレッジ目標 (Phase 2 §7.7): 純関数 80%+, state machine 70%+

### 7.2 NFR-13 自動検証ランナー (実行環境確定)

**実行環境**:
- 実機 (Phone + Glass) を adb 経由で接続
- Python スクリプト `tools/verify_atomicity.py` が `adb logcat -d -s channel.glass:* channel.phone-state:*` を取得
- Phase 5 で実装。CI には組み込まない (実機必要、リリース前手動実行)

**入力ログ形式** (§8 構造化ログ規約に従う):
```
2026-05-16T14:23:45.123Z I/channel.glass: event=glass_state_swap seq=42 mode=PERMISSION_CONFIRMING pending_request_id=req-abc-123 transcript_state=IDLE input_len=0 mic_source=GLASS
```

**検証ロジック** (擬似コード):
```python
def parse_kv_line(line):
    # "event=X key1=v1 key2=v2 ..." を dict に
    return dict(kv.split('=', 1) for kv in line.split() if '=' in kv)

INVARIANTS = [
    # (target_mode, predicate)
    ('PERMISSION_CONFIRMING', lambda e: e['pending_request_id'] != 'null'),
    ('LISTENING', lambda e: e['transcript_state'] == 'LISTENING'),
    ('CONFIRMING', lambda e: e['transcript_state'] != 'IDLE' or int(e['input_len']) > 0),
    ('IDLE', lambda e: True),
]

def verify(log_lines):
    violations = []
    for line in log_lines:
        if 'glass_state_swap' not in line and 'phone_state_emit' not in line:
            continue
        e = parse_kv_line(line)
        for (target_mode, pred) in INVARIANTS:
            if e.get('mode') == target_mode and not pred(e):
                violations.append((line, target_mode))
    if violations:
        print(f"FAIL: {len(violations)} violations")
        for v, m in violations: print(f"  [{m}] {v}")
        sys.exit(1)
    print(f"PASS: {len(log_lines)} lines, 0 violations")
```

**AC-09 判定**: 「Glass で permission 要求を 10 回連続再現」のシナリオを実機で実行 → logcat 取得 → ランナー実行 → 0 件で合格。

### 7.3 wire parity CI

```yaml
# .github/workflows/ci.yml (Phase 4 で作成)
- name: Kotlin tests
  run: ./gradlew :protocol:test

- name: TypeScript tests
  run: |
    cd hub && npm install && npm test
    cd ../bridge && npm install && npm test
```

両方の test が `golden/*` を読み込み、双方向 decode を確認。

---

## 8. ログ規約 (AD-19)

### 8.1 Logcat タグ命名

| タグ | 担当 |
|---|---|
| `channel.repo` | ChannelRepository |
| `channel.svc` | ChannelService / GlassConnectionService / MicForegroundService / VerdictDispatchService |
| `channel.client` | ChannelClient (HTTP/SSE) |
| `channel.wire` | wire encode/decode/send/recv |
| `channel.glass` | Glass relay / dispatcher / GlassBridge (Glass app 側) |
| `channel.tx` | Transcription |
| `channel.lifecycle` | AppLifecycleController |
| `channel.error` | WireError emit |
| `channel.phone-state` | Phone 側 currentState emit (NFR-13 検証用) |

Hub / Bridge (TS) も同じ命名で stdout に出力 (PM2 や systemd で aggregate するときの統一性のため)。

### 8.2 ログレベル基準

| level | 用途 |
|---|---|
| ERROR | WireError emit / 致命的失敗 / クラッシュ前のキャッチ |
| WARN | 復旧可能エラー / 想定外の状態 (例: register 前のメッセージ受信) |
| INFO | 重要な状態遷移 (FGS 起動/停止 / SSE open/close / Glass open/close / session 切替) |
| DEBUG | wire send/recv / state 更新 / detail-level event |
| VERBOSE | 原則使わない (DEBUG までで足りる) |

### 8.3 構造化ログ key=value 形式

```
event=<name> [key1=<v1>] [key2=<v2>] ...
```

例:
```
event=wire_send chat_id=abc123 session_id=def456 wire_event_name=reply
event=glass_state_swap seq=42 mode=IDLE pending_request_id=null transcript_state=IDLE input_len=0 mic_source=GLASS
event=fgs_state fgs_kind=glass transition_from=Starting transition_to=Running
```

**必須フィールド** (Phase 2 §7.14 + AD-19):
- `glass_state_swap`: `seq, mode, pending_request_id|null, transcript_state, input_len, mic_source`
- `phone_state_emit`: 同上
- `wire_send` / `wire_recv`: `chat_id?, request_id?, session_id?, wire_event_name`
- `permission_lifecycle`: `request_id, phase`
- `fgs_state`: `fgs_kind, transition_from, transition_to`
- `transcription_lifecycle`: `phase, mic_source`
- `error`: `error_class, error_message, chat_id?, request_id?, session_id?`

### 8.4 値の escape

`value` に空白 / `=` が含まれる場合は `key="value with spaces"` の形式で quote。

---

## 9. Phase 4 リポジトリ物理配置 (AD-20)

### 9.1 ディレクトリ構成

```
~/claude-mobile-hud/
├── .gitignore
├── README.md
├── docs/                         ← 設計書 (現状)
│   ├── 01-requirements.md
│   ├── 02-architecture.md
│   └── 03-detailed-design.md
├── settings.gradle.kts           ← Gradle root (protocol / phone / glass を include)
├── build.gradle.kts              ← root build script
├── gradle.properties
├── gradlew, gradlew.bat
├── gradle/
│   └── wrapper/
├── protocol/                     ← Kotlin library module (:protocol)
│   └── build.gradle.kts
├── phone/                        ← Phone Android app (Gradle subproject)
│   └── build.gradle.kts
├── glass/                        ← Glass Android app (Gradle subproject)
│   └── build.gradle.kts
├── hub/                          ← Hub TS (npm project, 独立)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
├── bridge/                       ← Bridge TS (npm project, 独立)
│   ├── package.json
│   ├── tsconfig.json
│   └── src/
├── claude-mobile-hud              ← CLI dispatcher (POC `claude-channel` 相当)
└── tools/
    ├── verify_atomicity.py       ← AC-09 ランナー
    └── pair-qr.ts                ← QR 生成 helper
```

### 9.2 Gradle root 構成

```kotlin
// settings.gradle.kts
rootProject.name = "claude-mobile-hud"
include(":protocol", ":phone", ":glass")
```

- `protocol` は library。Android 依存なし
- `phone` / `glass` は Android application、`implementation(project(":protocol"))` で参照
- Hub / Bridge は Gradle 管理外 (独立 npm project)
- CXR-L 用 cxrglobal は git submodule として `glass/cxrglobal/` 配下に置く (POC 通り)

---

## 10. Phase 4 (実装) への引き継ぎ

### 10.1 実装開始順序の推奨

1. **`:protocol` module** (依存元 / 他全てに影響)
2. **`:protocol` Kotlin golden 生成** (TS golden は Hub 着手時に追従)
3. **Hub** (Phone より先に動かして対向環境を用意)
4. **Bridge** (Hub とセット)
5. **Phone app** (Hub に繋いで開発)
6. **Glass app** (最後)

### 10.2 Phase 4 開始時に確定すべき技術スタック

Phase 4 着手前準備で確定済み (`gradle/libs.versions.toml` 参照):

| 項目 | バージョン |
|---|---|
| Kotlin | 2.2.10 |
| Gradle / AGP | 9.4.1 / 9.2.1 |
| Compose BOM | 2026.02.01 |
| kotlinx.serialization | 1.7.3 |
| kotlinx.coroutines | 1.9.0 |
| OkHttp | 5.1.0 |
| DataStore | 1.1.1 |
| Security Crypto | 1.1.0-alpha06 |
| JDK toolchain | 21 (Android Studio バンドル JBR) |
| Node / TypeScript | 22+ / 5.6+ |
| Vitest | 2.1 |
| JUnit Jupiter | 5.11.3 |
| Turbine | 1.1.0 |

### 10.3 完了基準 (Phase 4 → Phase 5 移行条件)

- 全クラスが本文書通りのシグネチャで実装されている
- 主要 user 操作が手動で動く (Phone send → reply 受信 / Glass permission verdict)
- Unit test がカバレッジ目標を満たす
- Wire parity CI が green

---

## 11. Phase 1 への遡及修正 (Rev 5、本 commit 同梱)

本フェーズの最終 commit で Phase 1 Rev 5 を同梱:

| 変更 | 理由 |
|---|---|
| FR-HU-14 文言: 「outstanding を `permission` イベントとして全て再 push」→ 「**`permission_snapshot { request_ids }` を createdAtMs 昇順で送出、続いて個別 `permission` イベントを送る**」 | AD-13 で permission_snapshot wire を新規追加したため。Phase 3 § 5.2.4 と整合 |
| FR-HU-15 (新規): Hub 再起動時、起動直後の outstanding は空集合として扱う (永続化しない) | AD-13 を要件レベルで明示 |

これにより Phase 3 と Phase 1 の整合性が維持され、Phase 4 着手時の追跡 (AC-05) が成立する。
