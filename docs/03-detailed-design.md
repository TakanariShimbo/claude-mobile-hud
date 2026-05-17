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
| AD-22 | Glass-side 受信は `CapsCodec.decode(bytes)` seam を貫けない (Rokid SDK が事前 Caps パース)。`CapsFactoryImpl.decodeFromCaps(Caps)` の fast-path を持つ。Phase 4 実機テストで判明 | §2.5.1 |

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

#### 2.5.1 Glass-side 受信は bytes seam を貫けない (Phase 4 修正、AD-22)

上記 `Codec`/`CapsCodec` の `decode(bytes: ByteArray)` 抽象化は **Phone-side のみで成立**
する非対称が Phase 4 step 5b の実機テストで判明した:

- **Phone-side 受信** (`com.example.cxrglobal.CXRLink.ICustomCmdCbk.onCustomCmdResult(key, payload: ByteArray)`):
    payload は raw bytes で渡される → `CapsCodec.decode(bytes)` で OK
- **Glass-side 受信** (`com.rokid.cxr.CXRServiceBridge.MsgCallback.onReceive(name, args: Caps?, bytes: ByteArray?)`):
    Rokid 公式 Glass SDK は wire を **事前に Caps 構造体にパースして `args` に詰めて配り、
    `bytes` を常に null にする** 設計。Pixel 8 + RG glasses で実機 logcat 確認済
    (`bytes_null=true args_null=false`)

`bytes ?: return` で受けると Phone→Glass の全 wire event が silent drop される。

**設計判断 (AD-22)**: Glass-side `CapsFactoryImpl` に `decodeFromCaps(caps: Caps): WireEvent?`
の Caps 直渡し fast-path を持たせる。`CapsFactory` interface (`:protocol`) には上げない
(`Caps` 型が `com.rokid.cxr` 依存、`:protocol` を Android-free に保つ制約のため)。Glass-side
の `GlassBridge.onReceive` は `args ?: return` 経由でこの fast-path を呼ぶ。bytes 経路は
Phone-side 受信および unit test (`Caps.fromBytes(bytes)` → `decodeFromCaps`) のために残す。

Phase 4 で「`:protocol` の bytes seam に揃える」リファクタを入れた際、`GlassBridge.onReceive`
が `args` (Caps) を直接読む経路と bytes 経路の非対称を見落として全 event を silent drop した
事故があった (Phase 4 の commit `576f597` で修正)。fast-path は `args` を直接読むので、
bytes 経路と並行で維持する必要がある。

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
            // AC-09 検証用 (§7.2)。`confirming` は CurrentState に乗せない
            // (mode に折り込み済み) ので draft 側から渡す。
            StructuredLog.phoneStateEmit(_currentState.value, draft.confirming)
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

##### 3.2.1.2.1 ConversationMode 優先順位 (Phase 4 で明文化)

`buildDraft` 内の `deriveMode` は以下の優先順位で **1 つだけ** 選ぶ:

```kotlin
private fun deriveMode(
    pendingForCurrent: PendingPermission?,
    transcriptState: TranscriptState,
    confirmingForCurrent: Boolean,
): ConversationMode = when {
    transcriptState == TranscriptState.LISTENING -> ConversationMode.LISTENING
    pendingForCurrent != null                    -> ConversationMode.PERMISSION_CONFIRMING
    confirmingForCurrent                         -> ConversationMode.CONFIRMING
    else                                         -> ConversationMode.IDLE
}
```

| 優先度 | mode | 条件 | 理由 |
|---|---|---|---|
| 1 | `LISTENING` | transcript_state == LISTENING | 録音中はユーザの発話を最優先。Hub から permission が来ても録音 UI を上書きしない (ユーザは「いま喋ってる」最中) |
| 2 | `PERMISSION_CONFIRMING` | 現 session に pending permission あり | LISTENING 終了後は permission 応答を急ぐ (Hub 側 timeout 持ちのため) |
| 3 | `CONFIRMING` | `_confirmingBySession[current] == true` | Glass の TAP で録音停止した直後の「送信 or 取消」選択画面 |
| 4 | `IDLE` | 上記いずれでもない | 通常入力可能状態 |

**§7.2 AC-09 verifier の判定順序もこの優先度に一致**させる (Rev 1 では PERMISSION > LISTENING の順で書いていたが、上記の表に揃えて修正済み)。

##### 3.2.1.2.2 `setConfirming` と CONFIRMING mode の駆動 (Phase 4 で明文化)

CONFIRMING mode は Glass gesture から driver される (Phone UI からは触らない):

| トリガ | 呼び出し | 結果 |
|---|---|---|
| Glass TAP で Listening 停止 | `setConfirming(currentSession, true)` | CONFIRMING mode 出現 |
| Glass SWIPE_FORWARD (送信) | `setConfirming(currentSession, false)` | CONFIRMING 解除 → send() |
| Glass SWIPE_BACK (取消) | `setConfirming(currentSession, false)` | CONFIRMING 解除 + input clear |
| `send()` 成功 | `setConfirming(resp.sessionId ?: sessionId, false)` | CONFIRMING 解除 (Hub mint された正規 id 優先) |
| `send()` 失敗 | flag 維持 | 入力欄が `handleSendFailure` で復元され、Glass UI も CONFIRMING のまま (再送可) |
| `ListeningCancel` (Glass cancel) | `setConfirming(currentSession, false)` | CONFIRMING 解除 + clearInput |

`_confirmingBySession: Map<String, Boolean>` は session 単位なので、別 session に切替えて戻ってきても残る。

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
            // FR-PH-34: 通知到達 = ユーザ注意が当該 session に向くべきタイミング。
            // ただし IDLE のときだけ auto-switch する gating を入れる (録音/送信確認/
            // 権限確認中はユーザ作業を邪魔しない)。詳細は §3.2.1.3.1。
            event.sessionId?.let { maybeAutoSwitchSession(it) }
            _events.tryEmit(ChannelEvent.Reply(event.chatId, event.sessionId, event.text))
        }
        is SseEvent.Permission -> {
            _pendingPermissions.update { current ->
                if (current.any { it.requestId == event.requestId }) current
                else (current + event.toPending()).toList()  // 新規参照
            }
            // FR-PH-45: permission も Reply と同じ gating で session 移動。
            event.sessionId?.let { maybeAutoSwitchSession(it) }
            _events.tryEmit(ChannelEvent.PermissionRequested(...))
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

##### 3.2.1.3.1 自動セッション切替 `maybeAutoSwitchSession`

Reply / Permission の受信 session が現 session と異なる場合、IDLE のときに限って自動切替する:

```kotlin
private suspend fun maybeAutoSwitchSession(targetSessionId: String) {
    val current = sessionStore.snapshot.value.currentSessionId
    if (current == targetSessionId) return
    if (_draft.value.mode != ConversationMode.IDLE) return
    selectSession(targetSessionId)
}
```

**ガード条件**:
- `target == current` → noop (同一 session)
- `mode != IDLE` → noop (LISTENING / CONFIRMING / PERMISSION_CONFIRMING 中はユーザ作業を尊重)

**race の整理**: `_pendingPermissions.update` 直後に `_draft.value.mode` を読むが、これは combine 経由で非同期更新される。ただし `target != current` の場合、新規 pending は `pendingForCurrent` には絞り込まれない (current session の filter で落ちる) ため、permission 追加が current の mode を `PERMISSION_CONFIRMING` に押し上げることはない。Reply 経路は mode に影響しないので同様。よって `_draft.value.mode` の遅延は判定を誤らせない。

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

**cleartext (Phase 4 開発時)**: Hub は plain HTTP (`http://...:8788`) なので、Android 9+ default の cleartext block を回避する必要がある。`phone/src/debug/res/xml/network_security_config.xml` (debug overlay) で `cleartextTrafficPermitted="true"` を有効化。release variant は `phone/src/main/res/xml/network_security_config.xml` の strict 設定 (cleartext 禁止 + system CA のみ) が当たる。Phase 5+ で Hub に TLS 終端を入れたら debug overlay は削除可能。

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

`messagesBySession: MutableMap<String, MutableList<ChatMessage>>` を Mutex 保護下で操作する。

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
    val errors: SharedFlow<PhoneWireError>  // BT SCO 失敗等の transient error

    fun setCurrentSession(id: String?)
    fun update(text: String)
    fun clear()
    fun startWithPhoneMic(apiKey: String)
    fun startFromGlass(apiKey: String)
    fun stop()
}
```

##### 3.2.5.1 session-per-draft 戦略

`InputController` は **session ごとに draft (入力欄テキスト) を分離保持** する (`Map<String, String> inputBySession`)。current session は Repository から `setCurrentSession(id)` で伝えてもらい、`text: StateFlow<String>` は current session の draft を派生させる。

理由: reply auto-switch (Phone IDLE 中の reply 受信で自動 session 切替、§3.2.1.3.1) や手動 session 切替が発生する設計のため、global な単一 draft だと「旧 session で書きかけた文字列が新 session に持ち込まれる」事故が起きる。

`update(text)` は `currentSessionId == null` の場合は破棄して warn log を残す (UI 側は入力欄を disable する契約)。`clear()` は current session の draft を消し、録音中なら `stop()` も呼ぶ。

##### 3.2.5.2 録音中の session 固定

録音開始時点の current session を `transcriptionSessionId` に固定し、partial / finalized event の書き込み先を確定する。**録音中の session 切替は UI 構造上発生しない** (録音中は会話画面に固定) 契約のもとで成立する。

`stop()` 経路では:

```
transcriptionSessionId = null    // ← 先に null にする
transcription.stop()             // 同期 teardown
audioRouter?.restore()           // routedBt なら復元
micSource = PHONE_FALLBACK
```

`transcriptionSessionId = null` を `transcription.stop()` より**前**に置くことで、`Repository.send` から `stop()` → 直後の `clearInput()` の間に inflight な finalized event が来ても、`transcriptionSessionId == null` で gating されて新 session に書き込まれない (P2-1 race 抑止)。

##### 3.2.5.3 `TranscriptionClient` ライフサイクル

公開 flow:
- `state: StateFlow<State>` — `Idle` / `Connecting` / `Listening(partial)` / `Error(message)`
- `finalized: SharedFlow<String>` — 確定 transcript (1 文単位)

**同期 vs 非同期** (P1-1, P1-2):
- `start` / `stop` は UI スレッドから呼ばれる前提で**状態遷移を同期に行う** (scope.launch を介さない)。呼び出し直後に `state.value` が更新されていることが Repository 側の `wasListening` 判定 (§3.4.2 `GestureKind.TAP`) で必要
- collector (transport.events.collect / mic.frames.collect) と `_finalized.emit` だけを `scope.launch` で切り出す。`transport.connect()` / `mic.start()` は block しない契約なので `start()` 本体から同期呼び出しでよい

**generation gating** (P1-4):
- `start` / `stop` / `teardownToState` のたびに `generation: AtomicInteger` を bump
- 遅延到着した event / frame は `routeFrame(gen, ...)` / `onEvent(gen, ...)` の冒頭で `gen != generation.get()` なら return
- `start → stop → start` を高速連打した際の race を構造的に防ぐ

`dispose()` は `stop()` + `scope.cancel()`。Application 終了時 / テスト後片付け用。

##### 3.2.5.4 pre-buffer 戦略

WS `connect` 直後 〜 OpenAI Realtime API の `session.update` ack 受信までの間に届いた audio frame は drop されることが実機で確認されている。`TranscriptionClient` は SessionReady (= session.update ack) を受けるまで `MicCapture` の frame を内部 `ArrayDeque<String>` (base64 済) に貯め、SessionReady で一括 flush する。

- **上限**: 250 frame (~10s @ 40ms 1 frame)
- **drop policy** (P2-3): 上限到達時は `pollLast` で**新しい方を捨てる**。pre-buffer の目的は session 冒頭の発話を保存することなので、`pollFirst` (古い方を捨てる) は逆効果

##### 3.2.5.5 Base64 エンコーダ選択 (P3-6)

`java.util.Base64.getEncoder()` を使用する。理由:
- OpenAI Realtime API は RFC 4648 padded (= `java.util.Base64` のデフォルト) を受け入れる (実機検証済)
- `android.util.Base64` を使うと **JVM unit test で empty string が返る** (Android 実機リンクされていないため)。テスト可能性を優先して `java.util.Base64` に統一

##### 3.2.5.6 Glass mic 経路と `BtScoUnavailable` contract (P1-3)

`startFromGlass` は `audioRouter?.routeToGlassMic()` を呼んで BT SCO ルーティングを試みる:

| `audioRouter` 状態 | `routeToGlassMic()` 戻り値 | 副作用 |
|---|---|---|
| `null` (4b2 段階で未注入) | — | `PHONE_FALLBACK` + `errors.emit(BtScoUnavailable)` |
| 注入済 | `true` | `MicSource.GLASS` + `routedBt = true` (stop 時に `restore()` を呼ぶ) |
| 注入済 | `false` (4c で BT SCO 取得失敗) | `PHONE_FALLBACK` + `errors.emit(BtScoUnavailable)` |

`errors` は `SharedFlow<PhoneWireError>` で Repository → UI に転送し、§3.7 の `BtScoUnavailable → Banner("内蔵マイクを使用中")` 契約に従って表示する (FR-GL-44 / §6.8)。

##### 3.2.5.7 backpressure 制御 (P2-4)

`finalized.emit` は `scope.launch { _finalized.emit(event.text) }` で切り出す。`onEvent` 内で直接 emit すると `SharedFlow` (SUSPEND policy) の subscriber が遅い場合に collector が止まり、後続の `Delta` / `Closed` event 処理まで遅延する。launch で切り離すことで event 流通そのものを保護する。

##### 3.2.5.8 4KB error blob のサニタイズ (P3-7)

`TranscriptionEvent.Error` の `message` は OpenAI API から最大数 KB の JSON が来る可能性があるので、`State.Error(event.message.take(512))` で 512 文字に切り詰めてから State に乗せる。warn log 側も `take(256)` で別途切り詰める。

### 3.3 service 層

#### 3.3.1 `AppLifecycleController` (state machine 完全列挙、Rev 2 修正)

FGS 間の直接結合を避けるオーケストレータ層の中核。Rev 2 では `pendingRestart` 擬似変数を廃し、状態を `sealed class` で完全列挙する。

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
- **partial failure 早期 abort**: `Starting` 中に boolean が一旦 true → false に振れた場合 (= 片方 FGS が `ON_CREATE` 直後にクラッシュし `ON_DESTROY` 通知が来た) は 5s watchdog を待たず即座に `Stopping(false)` に畳む。残った FGS への `stopGlassFgs` / `stopMicFgs` は idempotent なので両方呼んで構わない

**実装上の不変条件**:
- **`NonCancellable` で lifecycle 通知を消化**: `onFgsLifecycle` は `Application` scope の子で動くため、shutdown 中 (scope cancel 進行中) でも `mutex.withLock` 取得とフラグ (`glassFgsRunning` / `micFgsRunning`) 更新 + state 遷移評価までは `withContext(NonCancellable)` で守る。これがないと shutdown 中の `Stopping → Off` 遷移を取りこぼし、再起動後の state が stale で残る
- **`object` ではなく `class` + DI**: 設計書の `object AppLifecycleController` は意図伝達用 sketch。実装は `class` + `AppContainer` で singleton を持つ構成にして JVM unit test で `FgsOperations` を fake に差し替え可能にする

#### 3.3.2 `ChannelService`

`AppLifecycleController.startChannel` から起動される常駐 FGS-dataSync。役割:
- `ChannelRepository.connectivity` を購読し、通知文言を更新
- `ChannelRepository.events` を購読し、reply / permission 通知を post
- `ChannelRepository.pendingPermissions` の diff を購読し、消えた id の通知を `NotificationManager.cancel`
- **MicForegroundService を直接 start/stop しない** (FGS 同士の結合を避ける)

##### 3.3.2.1 permission 通知の 3 経路 cancel

permission 通知が消える経路は次の 3 つあり、すべて `pendingPermissions` の diff collector に集約する (idempotent なので重複呼び出し OK):

| 経路 | トリガ |
|---|---|
| 個別 verdict 成功 | `VerdictDispatchService` が extras 経由で `cancel` + `PermissionActionReceiver` が optimistic pre-dispatch cancel |
| `PermissionAbort` 受信 | Hub の abort event で `_pendingPermissions` から削除 |
| `permission_snapshot` で空集合 reconcile | Hub 再起動経路 (FR-HU-15) で全削除 |

3 経路すべてで `_pendingPermissions` が更新されると diff collector が即座に対応 notification を `cancel` する。

##### 3.3.2.2 cold-start gap (P2-1)

§5.3.1 で詳述した「`ChannelService` が死んでた間に Hub が発行+abort した permission 通知が shade に残る」問題は、`ChannelService.onCreate` で `CHANNEL_PERMISSION` の active 通知を **全 cancel** することで対処する。SSE 接続後の `permission_snapshot` + 個別 `permission` event (FR-HU-14) が必要なものを再 notify するので、幽霊通知は確実に消える。

```kotlin
override fun onCreate() {
    NotificationFactory.ensureChannels(this)            // P1-1 defensive: idempotent
    startForegroundCompat("起動中...")
    cancelStaleStartupPermissionNotifications()         // ← cold-start gap 対策
    observerJob = scope.launch { observe() }
}
```

`ensureChannels` を `onCreate` で叩くのは **P1-1 defensive**: `Application.onCreate` が先行する契約だが、`START_STICKY` 再起動経路で Application の再初期化が遅延した場合に通知が silent fail するのを防ぐ (idempotent なので副作用なし)。

##### 3.3.2.3 `START_STICKY` 選択理由 (P3-4)

`ChannelService` の役割は Hub 接続維持で、`Intent` 自体に処理対象データを持たない。kill 直後に Application が再 init される経路でも `PhoneApplication.onCreate` が先に走るため Repository も再構築済み。redeliver する command が無いので `START_REDELIVER_INTENT` ではなく `START_STICKY` を選ぶ。

#### 3.3.3 `GlassConnectionService`

`AppLifecycleController.startGlassSession` から起動される FGS-dataSync。役割:
- CXR-L 接続管理 (token 取得 → connect → L+BT → appStart → SessionOpen → 5s heartbeat)
- 自然切断検出時に **`AppLifecycleController.onGlassDisconnected(context)` を呼ぶ** (Mic FGS を直接知らない)
- onCreate / onDestroy で **`AppLifecycleController.onFgsLifecycle(GLASS_CONNECTION, ...)` を呼ぶ**
- 受信 payload を [`CapsCodec`] で decode し、companion の `events: SharedFlow<WireEvent>` に push (`GlassEventDispatcher` が collect、§3.4.2)
- 送信用 callback を companion の `sender: StateFlow<((ByteArray) -> Unit)?>` で公開 (`GlassRelay` が collect、§3.4.1)

##### 3.3.3.1 接続シーケンス

```
onStartCommand → CXRLink(this) を生成
  - configCXRSession(CUSTOMAPP, "com.example.claudemobilehud.glass")
  - setCXRLinkCbk(onCXRLConnected / onGlassBtConnected)
  - setCXRCustomCmdCbk(onCustomCmdResult: rk_custom_key 受信時 handleIncoming)
  - connect(token)

CXR-L L 接続 + Glass BT 接続が両方 true → CONNECTED
  → 1 回だけ cxrLink.appStart("...glass.MainActivity", IGlassAppCbk)

onOpenAppResult(true) / onGlassAppResume(true) のどちらかが返る (両経路あり)
  → sessionOpened を 1 度だけ true にして
  → SessionOpen wire を送信、_sender.value = sendCustomCmd、5s heartbeat 開始
```

heartbeat は `Handler(Looper.getMainLooper())` 経由で `HEARTBEAT_INTERVAL_MS = 5_000L`。`Ping(ts)` を `rk_custom_client` channel に送る。

##### 3.3.3.2 binder thread → main handler 集約 (P2-2)

CXR-L callback (`onCXRLConnected` / `onGlassBtConnected` / `onCustomCmdResult`) は **AIDL binder thread** で呼ばれる。state 更新 (`lConnected` / `btConnected` の compare-then-set や `refreshConnState`) を binder thread から直接行うと race を踏むので、すべて `mainHandler.post { ... }` で main thread に集約する。

##### 3.3.3.3 stop/start race と `currentInstance` ガード (P2-1)

companion state (`_sender` / `_connState` / `currentInstance`) は process singleton。古いインスタンスの `onDestroy` が新しいインスタンスの companion state を消さないよう、`@Volatile var currentInstance: GlassConnectionService?` で**現役 pointer** を持ち、`onDestroy` 内で `currentInstance === this` のときだけ state をリセットする:

```kotlin
override fun onCreate() {
    currentInstance = this    // 上書きで新インスタンスを現役化
    ...
}

override fun onDestroy() {
    ...
    if (currentInstance === this) {
        currentInstance = null
        _sender.value = null
        _connState.value = GlassCxrState.DISCONNECTED
    }
}
```

##### 3.3.3.4 自然切断検出

`refreshConnState` が `prev == CONNECTED && next != CONNECTED` を検出したら **`AppLifecycleController.onGlassDisconnected(context)`** を呼ぶ。これにより Glass FGS + Mic FGS を `Stopping(false)` に畳む経路に入る (§3.3.1 遷移表)。

##### 3.3.3.5 `appStart` の冪等化 (P2-4)

`IGlassAppCbk.onOpenAppResult(true)` と `IGlassAppCbk.onGlassAppResume(true)` は **両方 `onAppOpened()` 経路に来る**。重複 `SessionOpen` 送信を避けるため `sessionOpened` boolean で 1 回ガードする。

##### 3.3.3.6 受信 payload の SharedFlow emit (P2-5)

`onCustomCmdResult(key = rk_custom_key, payload)` で受けた payload は `codec.decode()` で `WireEvent` に戻し、`_events.tryEmit(event)` で `SharedFlow` に push する。buffer 満杯 + subscriber 不在で `tryEmit` が false を返した場合は silent drop を避けるため warn log を残す。

##### 3.3.3.7 `START_STICKY` 選択 + `onDestroy` の `SessionClose` (P2-3)

`onStartCommand` は `START_STICKY` 戻し。OS kill 後 redeliver で `TokenStore.token.value` を読み直し接続復帰する。`onDestroy` は `mainHandler.removeCallbacks(heartbeat)` → `SessionClose` wire 送信 (best-effort runCatching) → `cxrLink.disconnect()` の順で畳む。token 更新で running session を再接続する hook は 4c2 検討項目 (P3-3)。

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

**baseUrl / token 冗長化のトレードオフ** (P1-2 acknowledged):

通知タップ時に app process が kill されている可能性があるため、PendingIntent extras に baseUrl と token を冗長化して NFR-14 (5s) 予算に収める。代償として **settings 更新 (token rotation) 後にこの通知をタップすると旧 token で POST し 401 が返る可能性** がある。許容理由:

- permission 通知の表示寿命は短い (Claude 側 timeout で abort される)
- token rotation 直後の outstanding 通知は数件オーダー
- kill 経路を捨てて in-proc に統一すると FR-PH-43 (kill 中通知応答) を失う

将来 settings 更新時に既存通知を再生成する hook を入れれば緩和可能 (4c+ 検討項目)。

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

#### 3.3.6 `PhoneApplication` (bootstrap / 手動 DI)

`Application` サブクラス。Hilt は本プロジェクト範囲では入れず **手動 DI コンテナ** (`AppContainer`) を `onCreate` で組み立てる。

##### 3.3.6.1 起動順序

```
1. TokenStore.load(this)                    EncryptedSharedPreferences を load
2. NotificationFactory.ensureChannels(this) 通知 channel 作成 (idempotent)
3. AppContainer を build                     Repository / AppLifecycleController / GlassRelay /
                                            GlassEventDispatcher を singleton 化
4. scope.launch { repository.initialize() } 履歴 / settings 復元 (完了は待たない、
                                            UI は uiState の collect で逐次更新)
5. lifecycle.startChannel(applicationContext)  ChannelService を FGS 起動
6. glassRelay.start() / glassEventDispatcher.start()
                                            sender が null の間は no-op なので早期起動 OK
```

##### 3.3.6.2 `containerOrNull` の生存判定

```kotlin
@Volatile private var _container: AppContainer? = null
val containerOrNull: AppContainer? get() = _container
val container: AppContainer get() = _container
    ?: error("AppContainer accessed before PhoneApplication.onCreate finished")
```

`Application` は process kill 中も "exists" だが、`AppContainer` は `onCreate` 完了後にしか触れない。kill 経路から動く `PermissionActionReceiver` (§3.8) は **`containerOrNull` で in-proc / out-of-proc を判定** し、in-proc なら Repository を直接叩き、out-of-proc なら `VerdictDispatchService` 経由で送信する。通常コード経路は `container` (non-null assert) を使う。

##### 3.3.6.3 `onTerminate` の扱い

`Application.onTerminate` は **エミュレータでしか呼ばれない** 契約 (実機では process kill が先)。`AppLifecycleController.shutdownAll` を best-effort で呼ぶが、実機での片付けは Android OS の process kill に任せる。

##### 3.3.6.4 `RealFgsOperations` の MIC FGS eligibility 二重ガード

`AppLifecycleController.FgsOperations.startMicFgs` は **`startForegroundService` を呼ぶ前に eligibility を判定** する:

- `RECORD_AUDIO` runtime granted
- App が `STARTED` 以上 (foreground)

不適合なら **`startForegroundService` 自体を呼ばない**。Android 14+ では:
- `startForegroundService` を呼んだ後 5s 以内に `startForeground(MICROPHONE)` を呼ばないと `ForegroundServiceDidNotStartInTimeException` が飛ぶ
- 上記条件を満たさない状態で `startForeground(MICROPHONE)` を呼ぶと `SecurityException`

`MicForegroundService.onCreate` 側にも同じ guard を置いてあるが、こちらは OS triggered restart 等の double-defense (dispatcher 段階で塞ぐのが第一防衛線)。

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
    // FR-GL-20: Glass の session 一覧は active のみ。Phone 側 SessionDrawer は
    // 履歴アクセスを兼ねるため inactive も dot 区別付きで表示する (UI 意図差分)。
    launch { repository.uiState.map { it.sessions.filter { s -> s.isActive } }.distinctUntilChanged().collect { sendSessionList(sender, it) } }
    launch { repository.uiState.map { it.currentSessionId }.distinctUntilChanged().collect { sendCurrentSession(sender, it) } }
    launch { repository.uiState.map { it.currentSessionId to it.messages }.distinctUntilChanged().collect { sendMessages(sender, it) } }
    launch { observeNotifications(sender) }
}
```

**Glass / Phone の session 一覧表示の UI 意図差分** (FR-GL-20 / FR-PH-50):

| | 表示対象 | 理由 |
|---|---|---|
| Glass (`SessionList` wire、payload は `it.toWirePayload()` で `SessionSummaryPayload` に写像) | `isActive == true` のみ | Glass は「いま操作対象に出来る session」を選ぶ画面。inactive は出さない |
| Phone (`SessionDrawer`) | active + inactive 全部、active には dot | Phone は履歴アクセスも兼ねたドロワー。inactive を見せて過去履歴に飛べるようにする |

FR-PH-50 / FR-GL-20 の要件文は同一 (「アクティブなセッションを一覧表示する」) だが、Phone のドロワー UI 側はユーザ判断で「inactive も dot 区別で含める」運用に倒している (Phase 4 確定)。

**`current_session` wire は filter しない** (`observeCurrentSession` は `currentSessionId` をそのまま送る):
- `SessionInactive` 受信時、`SessionStore` は `activeSessionIds` から該当 id を外すが `currentSessionId` 自体はクリアしない。
- 結果として「list には居ないが current は指したまま」の組合せが Glass に届き得る。Glass 側 (`SessionSelectScreen.indexOfFirst`) は `-1` ガード済みでクラッシュしない。
- session を切り替えるかどうかはユーザ判断に任せたいので、この挙動を維持する。

**wire payload に `isActive` フィールドは持たせない**: 現状 Phone 側で filter する設計なので `SessionSummaryPayload` には `isActive` が無い。将来 Glass 側で「inactive を別 UI で出す」要件が追加された場合は wire 拡張 (= 互換性破壊変更) が必要 (Phase 5+ で検討)。

##### 3.4.1.1 `refresh()` による observer 再起動

Glass プロセスが再起動して Hello を送ってきた場合、Phone 側の state は不変なので各 StateFlow から新値が流れず、Glass は古いスナップショットのまま動けない。これを解決するため `GlassRelay` は `refreshSignal: MutableStateFlow<Int>` を持ち、`refresh()` (Glass hello を `GlassEventDispatcher` 経由で受けたとき) で bump する:

```kotlin
fun refresh() {
    refreshSignal.update { it + 1 }   // P2-7: 並行 refresh の race 回避
}

// start() 内
refreshSignal.collectLatest {
    coroutineScope {
        launch { observeCurrentState(sender) }
        launch { observeSessionList(sender) }
        launch { observeCurrentSession(sender) }
        launch { observeMessages(sender) }
    }
}
```

`collectLatest` が前回の `coroutineScope` を cancel し、observer 群を完全に再起動する。これにより `StateFlow.collect` の再 subscribe で現在値が distinctUntilChanged を貫通して再 emit され、Glass が現スナップショットを受け取る。

##### 3.4.1.2 `observeNotifications` を `refreshSignal` の外側で起動

`repository.events` (reply / permission notifications) は **replay=0 の `SharedFlow`** で配信される一過性 event なので、refresh で取り直すことができない (subscriber 再起動時に過去 event を replay しない契約)。そのため `observeNotifications` は `refreshSignal.collectLatest` の cancel scope の **外側** で 1 度だけ起動する:

```kotlin
launch { observeNotifications(sender) }       // refresh の外側
refreshSignal.collectLatest { ... }            // この中の observer だけ refresh で再起動
```

`SharedFlow` の subscriber 再起動は event の取りこぼしを起こすので、notifications は sender が接続している間ずっと同一 subscriber で受け続ける。

##### 3.4.1.3 sender unavailable / 送信失敗の扱い

- `GlassConnectionService.sender: StateFlow<((ByteArray) -> Unit)?>` は CXR-L 接続中だけ non-null。`start()` は `collectLatest` で sender を観測し、`null` の間はすべての observer を起動しない。Glass 接続確立で non-null になると observer 群が起動する
- `sendWire()` は `codec.encode` 失敗 / `sender(payload)` 失敗を `runCatching` で吸収し warn log のみ残す (P2-8)。`ts` と event class 名を log フィールドに含めて重複検出を容易にする。送信失敗は CXR-L 一時障害なので UI には出さず、次の StateFlow 更新で再送される

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
        }
        is PermissionVerdictEvent -> repository.respondPermission(event.requestId, event.decision)
        is Ping -> { /* heartbeat echo は silently drop (P3-7) */ }
        else -> { /* Phone 向けイベントが Glass 経由で来ることはない */ }
    }
}
```

##### 3.4.2.1 `handleGesture` (Phase 4 で明文化)

```kotlin
private suspend fun handleGesture(which: GestureKind) {
    // gesture 受信時点の current session を冒頭で snapshot し、setConfirming に
    // 明示的に渡す。auto-switch / 通知タップで途中で session が切替わっても
    // 別 session に flag が立たないようにする (P2-A of review)。
    val sessionIdAtGesture = repository.uiState.value.currentSessionId
    when (which) {
        GestureKind.TAP -> {
            // Listening 中の停止のみ confirming flag を立てる (Idle → 録音開始は触らない)。
            // wasListening 判定は toggleTranscription() 前に評価しないと、stop 後の
            // state を見て false 判定になる race を踏む。
            val wasListening = repository.input.transcription.state.value.let {
                it is TranscriptionClient.State.Listening ||
                    it is TranscriptionClient.State.Connecting
            }
            toggleTranscription()
            if (wasListening) repository.setConfirming(sessionIdAtGesture, true)
        }
        GestureKind.SWIPE_FORWARD -> {
            // 送信確定: confirming を畳んでから send()。inputText snapshot を
            // 同期的に取ることで、launch 内で前回 send の clearInput() 結果を
            // 読んでしまう race も防ぐ (NFR-13 atomicity)。
            repository.setConfirming(sessionIdAtGesture, false)
            val snapshot = repository.inputText.value
            repository.send(snapshot)
        }
        GestureKind.SWIPE_BACK -> {
            // 取消: confirming を畳んで input をクリア。
            repository.setConfirming(sessionIdAtGesture, false)
            repository.clearInput()
        }
        GestureKind.DOUBLE_TAP -> { /* Glass 側で session 選択画面に戻す。Phone 無処理。*/ }
    }
}
```

詳細な CONFIRMING mode の駆動表は §3.2.1.2.2 を参照。

### 3.5 UI 層

#### 3.5.1 `MainScreen` 分解

`MainScreen` を 1 個の巨大 composable にせず、**3 個の subcomposable + state holder 1 個** に分解する:

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

**判断**: **JSON** で持つ。理由:
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

##### 3.6.4.1 PendingIntent `requestCode` の採番ルール (P1-3)

reply / permission 通知の `PendingIntent.getActivity` は `requestCode` に **呼び側 (`ChannelService`) で確保したユニークな `notificationId`** を渡す。理由: Android の PendingIntent は `(target, requestCode)` ペアで一意化されるため、同じ `requestCode` で `FLAG_UPDATE_CURRENT` を付けて再生成すると **既存 PendingIntent の extras を上書き** してしまう。

現行の `notificationId` 採番 (`ChannelService.kt`):

| 通知種別 | notificationId の式 | 単位 |
|---|---|---|
| reply | `NOTIF_REPLY + sessionId.hashCode()` | session 単位 (同 session の 2 件目は既存通知を update) |
| permission | `NOTIF_PERMISSION_BASE + requestId.hashCode()` | request 単位 (各 request が別通知) |

reply は session 単位なので、同じ session への複数 reply は **同じ通知を update** し最後の reply を表示する (= 過去の reply の extras も最新で上書きされて問題ない)。permission は request 単位なので衝突しない。

旧設計 (Phase 3 開発中) では `(sessionId | kind | requestId)` 合成ハッシュを `requestCode` にしていたため、同じ session に対する複数の通知種別が同一 `requestCode` に衝突し、後から作った PendingIntent が前の extras を上書きしていた。現行スキーマで解消 (P1-3)。

**permission action button** (Allow / Deny) の `PendingIntent.getBroadcast` も同じ問題があるので、`notificationId * 2` (allow) と `notificationId * 2 + 1` (deny) で `requestCode` を分離する。

すべての PendingIntent は `FLAG_IMMUTABLE` 必須 (targetSdk 31+ で必須化、`PendingIntent.FLAG_MUTABLE` を明示しない限り IMMUTABLE)。

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

`PhoneState` を 1 つの上流とする state holder。詳細 (sealed State / SendChoice / PermissionChoice) は Rev 1 通り。

### 4.5 `MainActivity` (Activity host)

Glass app は **single Activity**。役割:
- `GlassBridge.init(applicationContext)` で CXR-L 受信を開始 (process singleton)
- `SoundEffects.init(applicationContext)` で sfx 用 application context を登録 (Compose 非依存層から context-less `play(Kind)` を呼べるようにするため)
- Compose host (`GlassNavHost` + `PhoneConnectionGate`)
- 物理リモコンのキー入力を [GestureBus] に流す (FR-GL-11)
- `GlassBridge.notifications` 観測 → 画面 wake + chime + nav

`setShowWhenLocked(true)` + `setTurnScreenOn(true)` を `onCreate` で立て、画面 OFF / ロック中の通知受信でも Activity を出せるようにする。

#### 4.5.1 通知ハンドラ

`GlassBridge.notifications` を `lifecycleScope` で collect し、各通知に対し:

1. `ScreenAwakeManager.wakeOnNotification(this)` で display wake (干渉ガードは Manager 内に閉じている)
2. `SoundEffects.play(this, notif.kind)` で kind に応じた chime (reply=1 chime / permission=2 連 chime、FR-GL-71 は `SoundEffects` 内に閉じる)
3. `SessionNavigator.requestConversation()` で conversation 画面へ nav 要求

current session を切り替えるかどうかの判定は **phone 側 IDLE 時のみ reply で auto-switch** する設計に閉じているので、glass から `sendSelectSession` は送らない。reply / permission どちらの kind でも nav 要求は同じで、既に CONVERSATION なら下記 §4.5.3 のガードで no-op。

#### 4.5.2 UI フィードバック音 (sfx) のトリガ

3 種の sfx を Activity scope で検出する:

| sfx | トリガ | 実装場所 | 理由 |
|---|---|---|---|
| `RECORD_START` | TAP 押下時点 (状態遷移を待たない) | `ConversationStateHolder.handleIdle` | 状態遷移ベースだと CXR-L round-trip ぶん遅延する。Phone mic capture は 50-200ms 後に始まり、sfx 末尾が capture に乗っても発話冒頭はカットされない |
| `RECORD_STOP` | `phoneState.transcriptState` が `LISTENING → 非LISTENING` に遷移 | `MainActivity` | 起動直後の collect で誤発火しないよう、`prev == null` の初回 emission は鳴らさない |
| `SEND` | `messagesForSession` の OUTGOING 最大 id 増加 | `MainActivity` | `HistoryStore` の autoinc id は **session 横断**なので、session 切替で他 session の高 id を新規送信と誤認する可能性あり。sessionId と messages を同一 wire event (`MessagesEvent`) 由来の atomic pair で観測し、session 変更を検出した emission は baseline 記録のみで音は鳴らさない |

#### 4.5.3 nav backstack 保護

`SessionNavigator.requests` を `LaunchedEffect(nav)` で collect し、既に `CONVERSATION` ルートに居る場合は `nav.navigate` を呼ばない:

```kotlin
LaunchedEffect(nav) {
    SessionNavigator.requests.collect {
        if (nav.currentDestination?.route == GlassRoutes.CONVERSATION) return@collect
        nav.navigate(GlassRoutes.CONVERSATION) {
            popUpTo(GlassRoutes.SESSION_SELECT) { inclusive = false }
            launchSingleTop = true
        }
    }
}
```

`popUpTo` + `launchSingleTop` の組合せでも、同じ destination に `navigate` すれば `NavBackStackEntry` が一度 pop → 再生成され、`ConversationStateHolder` の `remember` 状態が消える。CONVERSATION 滞在中は no-op に倒すことで state を保護する。

#### 4.5.4 物理リモコン (`dispatchKeyEvent`)

Rokid Glass のキーマッピング:

| KeyCode | Gesture |
|---|---|
| `KEYCODE_ENTER` (中央押し) | `Tap` |
| `KEYCODE_DPAD_RIGHT` | `SwipeForward` |
| `KEYCODE_DPAD_LEFT` | `SwipeBack` |
| `KEYCODE_BACK` | `DoubleTap` (= 戻る) |
| `KEYCODE_DPAD_UP` / `_DOWN` | 握りつぶし (no-op) |

`BACK` は `OnBackPressedDispatcher` に流すと Activity finish するため、`dispatchKeyEvent` で完全に乗っ取り `GestureBus` に `DoubleTap` として emit する。`ACTION_UP` / `LONG_PRESS` は emit せず握りつぶす (`ACTION_DOWN` 1 回のみ)。`@Suppress("GestureBackNavigation", "RestrictedApi")` で lint 警告を抑制。

### 4.6 構造化ログ

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
4. Phone 側 `ChannelService` が `pendingPermissions` の diff (= 全削除) を検知し、
   shade に残っていた permission 通知を `NotificationManager.cancel` する

Bridge が順次再接続して register してきたら通常運用に戻る。

**ただし重要な運用注意**: `Bridge` は Hub からの remote close (TCP 切断) を検知すると、
`bridge/src/index.ts` の `onClose` ハンドラで `shutdown("hub_remote_close")` を呼んで
**自殺する**。これにより Hub crash 時の処理は以下のセット運用が前提:

- Hub crash → Bridge も自殺 → claude プロセスは alive のまま MCP server (= Bridge)
  不在で stuck (CLI 画面は permission elicitInput のまま固まる)
- Phone 側は AD-13 通り、Bridge 不在で Hub 再起動後の SSE snapshot を受けて幽霊 pending
  と shade 通知を一掃する → ユーザ視点では「Phone は綺麗な状態」
- ユーザは **claude wrapper を Ctrl-C で停止 → 再起動** する必要がある (Hub 単独再起動
  はサポートされない)

Phase 4 ではこの「Bridge 自殺 + wrapper 再起動」が異常系のサポート運用。Phase 5 以降で
Bridge auto-reconnect + outstanding resend を実装すれば、Hub 単独再起動も透過的に乗り
越えられる (= 設計拡張余地、本書 Phase 5 引き継ぎに記載)。

##### 5.3.1 Phone 側 cold-start gap (Phase 4 で対処)

`ChannelService` (Phone) が死んでた間に Hub が permission を発行 + abort、その間に
Application が再起動した場合、SSE 接続前の `_pendingPermissions` は空で start するので
diff collector が見逃すケースがある (shade に幽霊通知が残る)。対処として `ChannelService.
onCreate` で `CHANNEL_PERMISSION` の active 通知を一旦全 cancel し、SSE 接続後の `permission`
個別 event (FR-HU-14 で snapshot 後に続く) で必要なものを再 notify させる方式を採用。

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

#### 6.2.3 `SessionDetector` (Phase 4 改訂: env injection 方式)

Bridge 起動時に `process.env.BRIDGE_SESSION_ID` を読み、UUID 正規表現で validate した上で session_id として確定する。**`/proc` 探索 / 親プロセス walk / cmdline parse は しない** (代替案として検討した process-walk 方式の欠点は後述)。

env が未設定 / 空 / 不正 UUID の場合は **即 throw** (random UUID fallback は AD-12 相関 ID 伝播を静かに壊すため絶対禁止)。

##### session_id の single source of truth = wrapper (`claude-mobile-hud run`)

session_id は wrapper が `uuidgen` で 1 つ生成し、**同じ UUID を 2 方向に push** する:

| 経路 | 用途 |
|---|---|
| claude の起動引数 `--session-id <uuid>` | `~/.claude/projects/<slug>/<uuid>.jsonl` の履歴 slug を確定 |
| `.mcp.runtime.json` の `env.BRIDGE_SESSION_ID` | Bridge → Hub `register` の session_id と一致させる |

この双方向 inject により Bridge / claude / Hub の session_id が **構築時に既に一致** している。Bridge が起動後に逆引きで session_id を割り出す必要が無い (= AD-12 を実装単純化で支える)。

`.mcp.runtime.json` は wrapper が毎回 `node -e JSON.stringify(...)` で動的生成する (heredoc 直書きは tsx 等の絶対パスに `"` / `\` が混じった瞬間 JSON が壊れるので避ける):

```json
{"mcpServers":{"channel":{"type":"stdio","command":"<tsx>","args":["<bridge entry>"],"env":{"BRIDGE_SESSION_ID":"<uuid>"}}}}
```

wrapper はその後 `exec claude --mcp-config <生成 path> --session-id <uuid> --dangerously-load-development-channels server:channel [yolo なら --dangerously-skip-permissions] "$@"` で claude を引き継ぐ。`--dangerously-load-development-channels server:channel` は Bridge が emit する `notifications/claude/channel*` を claude に届ける gate flag (Claude Code 2.x で `--help` から hidden になったが flag 自体は受理される)。

##### env injection を採る根拠 (process-walk 代替案の却下)

`.mcp.json` を静的に書いて `--mcp-config` で渡す素朴な構成だと、Bridge 起動時点で session_id が wrapper 側から見えないため、**`/proc/<ppid>/cmdline` を最大 10 hop 親方向に登って claude 祖先を探し、その cmdline から `--session-id` を抜く** ような process-walk 方式に頼らざるを得ない。本プロジェクトは wrapper が runtime config を毎回生成するので env templating ができ、process-walk の問題:

- Linux 限定 (`/proc` 依存)
- Claude Code 2.x が MCP child を node ラッパー経由で spawn → ppid 1 hop では届かない
- spawn topology のバージョン差で壊れる fragility

を回避できる。Bridge 側のコードも process-walk 方式比で ~3 倍小さくなる (env を 1 行読むだけ vs `/proc/<ppid>` を最大 10 hop 登る + 各 step で cmdline parse)。

##### scope 外

- 並列 `run` (同一 checkout で複数 claude セッション同時起動) は未サポート (`.mcp.runtime.json` が race で上書きされる)。Phase 4 は 1 user 1 session 想定。並列が必要になったら wrapper を `mktemp` ベースに切り替える。

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
    # §3.2.1.2.1 の優先順位 (LISTENING > PERMISSION_CONFIRMING > CONFIRMING > IDLE) に
    # 揃える。録音中は permission 出現でも mode を奪わない設計なので、LISTENING を先頭。
    # CONFIRMING は `_confirmingBySession[current]==true` で出るので transcript ではなく
    # 構造化ログに新 key (confirming=true/false) を持たせて参照する想定 (Phase 5 実装時)。
    ('LISTENING', lambda e: e['transcript_state'] == 'LISTENING'),
    ('PERMISSION_CONFIRMING', lambda e: e['pending_request_id'] != 'null'),
    ('CONFIRMING', lambda e: e.get('confirming') == 'true'),
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
├── claude-mobile-hud              ← CLI dispatcher (hub / pair / run / resume 等)
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
- CXR-L 用 cxrglobal は git submodule として `glass/cxrglobal/` 配下に置く

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

### 10.4 Phase 4 完了報告 (2026-05-17)

実機 (Pixel 8 + Rokid Glass) + PC (Ubuntu / Hub + Bridge) で主要シナリオを
通し、§10.3 の完了基準を満たした:

- ✅ 全クラスが本文書通りのシグネチャで実装
- ✅ 主要 user 操作が手動で動く (#171–#177 シナリオ確認):
  - 通知タップ session 遷移 (起動中 / bg)
  - 画像添付送信 (claude が画像を認識する)
  - Hub 再起動 → permission_snapshot reconcile
  - AuthFailed → 手動 reconnect
  - kill verdict (inproc 経路、cold-start path は Phase 5)
  - BT SCO 失敗時の Phone マイク fallback (Phone マイク経路、自動切替は Phase 5)
- ⏳ Unit test カバレッジは部分達成 (test infra 整備が必要、Phase 5 持ち越し)
- ✅ Wire parity CI (`.github/workflows/ci.yml`) all green

#### 10.4.1 Phase 4 終盤で発見・修正したバグ (設計穴の発掘)

Phase 4 実装の最終段階で 5 件の設計穴 / 実装抜けが見つかり、本書 §3 / §5 と
合わせて修正した:

| 修正 commit | 概要 | 設計穴の場所 |
|---|---|---|
| `519e8af` | Glass session list を active のみに filter (FR-GL-20 抜け) | §3.4.1 |
| `ceedafe` | 画像添付の base64 化が `// TODO` のままで Hub に送られていなかった | §3.2.1.1 / §6.2.4 |
| `d894b87` | Hub 再起動経路で permission 通知が cancel されない (FR-HU-15 の Phone 側不在) + cold-start gap 対処 | §5.3 / §5.3.1 |
| `56deee1` | non-ASCII token を `IllegalArgumentException` ではなく `AuthFailed` に翻訳 | §3.2.2 |
| `66f8b62` | AuthFailed 時の send/verdict 連打で UI が flapping するのを early return で gate | §3.2.1 |

#### 10.4.2 Phase 5 への引き継ぎ

| 項目 | task | 補足 |
|---|---|---|
| GlassRelay unit test (session list active filter / current_session 非フィルタ含む) | #179 | test infra (FakeChannelRepository) の整備込み |
| BtAudioRouter fallback 自動切替の unit test | #183 | 実機再現不能 (CXR-L が BT 経由のため Glass connection が先に切れる) |
| VerdictDispatchService cold-start NFR-14 (5s) 実機測定 | #182 | force-stop で通知消失 / am kill で FGS 拒否 のため §7.2 AC-09 logcat 解析と一緒に枠組み化 |
| AC-09 自動検証 ランナー実装 | — | §7.2 既載 (実機 logcat → Python `verify_atomicity.py`) |
| **wire parity 本格 (NFR-50 / AC-06)** | — | §7.3 既載。Phase 4 では `bridge/test/wire-golden-smoke.test.ts` で snake_case + JSON valid の smoke のみ。zod schema 化 + Phone↔Hub の Kotlin/TS field 一致 + Bridge↔Hub の TS 同士 schema 共有を Phase 5 で本実装 |
| Bridge auto-reconnect + outstanding resend | — | §5.3 既載 (Hub 単独再起動を透過化、現在は wrapper 全停止再起動が運用) |
| Hub TLS 終端 | — | §3.2.2 既載 (debug overlay の cleartext を Phase 5 で削除可能) |
| Settings 保存時 input validation | (#181 nit) | non-ASCII / 空 token を入力段階で弾いて round-trip を省く UX 改善 |

#### 10.4.3 Phase 5 進捗 (2026-05-17 時点)

§10.4.2 の引き継ぎ項目のうち、Phase 5 ステップ §1〜§5 を消化:

| Phase 5 step | 内容 | 状態 | commit |
|---|---|---|---|
| §2 test infra + #179 / #183 | AudioManagerLike 抽象化 + GlassRelay mapping 抽出 + 18 unit tests | ✅ | `f6207b6` |
| §3 AC-09 verify_atomicity.py | logcat invariant 検証 + Phone side `phone_state_emit` event 整合 + self test 11 件 + 実機 24 events 0 violations 確認 | ✅ | `6d963be` |
| §4 README (AC-07 セットアップ再現性) | 第三者が clone → 起動できる手順書 + CI badge + 既知の制限明記 | ✅ | `dc1a6a9` |
| §5 NFR 計測ツール群 | NFR-14 verdict latency (#187) + NFR-10 SSE 再接続 latency (#188) の logcat 解析スクリプト + self test 18 件 | ✅ | `8655010` / `39e8992` |
| §6 拡張実装 (一部) | Settings token 入力 validation (#189) + wrapper subcommands rotate-token/resume/list-sessions (#190) | ✅ | `53660ae` / `ba60b9e` |

**Phase 5 完了 (2026-05-17)**。検証手段 (test infra / AC-09 verifier / NFR 計測ツール) と運用ツール (wrapper subcommands / docs) が揃った。

#### 10.4.4 Phase 6 への引き継ぎ

Phase 5 で対応保留したものを Phase 6 (リリース・移行) で消化:

| 項目 | task | 補足 |
|---|---|---|
| **#182 VerdictDispatchService cold-start NFR-14 実機測定** | #182 | `tools/measure_verdict_latency.py` が `verdict_dispatch_started` event をサポート済み。kill 状態シナリオを実機で実走 (force-stop で通知消失 / am kill で FGS 拒否 のため特殊実機 setup が要る) |
| **AC-04 SSE 再接続シナリオ実走** | (新規) | `tools/measure_reconnect_latency.py` を使い ≥10 回切断 → 中央値 < 30s を実機で確認 |
| **NFR-40 電池 SLO 計測** | (新規) | Battery Historian + 10h 連続実機テスト |
| **Hub TLS 終端 + release variant** | (新規、§6 D + E) | セットで実装。証明書管理 + ProGuard rules + Hi Rokid 認可 release path + release `network_security_config` (cleartext 禁止) |

Phase 5 で「やらない」判断:

| 項目 | 判断 |
|---|---|
| Bridge auto-reconnect + outstanding resend (§6 A) | docs §5.3 の「Hub 単独再起動は非サポート、wrapper 全停止再起動が正規運用」を維持。Phase 5 で実装スコープから除外 (ユーザ判断 2026-05-17) |

---

## 11. Phase 1 への遡及修正 (Rev 5、本 commit 同梱)

本フェーズの最終 commit で Phase 1 Rev 5 を同梱:

| 変更 | 理由 |
|---|---|
| FR-HU-14 文言: 「outstanding を `permission` イベントとして全て再 push」→ 「**`permission_snapshot { request_ids }` を createdAtMs 昇順で送出、続いて個別 `permission` イベントを送る**」 | AD-13 で permission_snapshot wire を新規追加したため。Phase 3 § 5.2.4 と整合 |
| FR-HU-15 (新規): Hub 再起動時、起動直後の outstanding は空集合として扱う (永続化しない) | AD-13 を要件レベルで明示 |

これにより Phase 3 と Phase 1 の整合性が Phase 4 着手時点で維持される。
