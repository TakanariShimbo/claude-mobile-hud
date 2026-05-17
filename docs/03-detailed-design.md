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

#### 2.2.1 `ts` (epoch ms) は `Long` で JS Number 安全

全 WireEvent の `ts` は epoch ミリ秒の `Long`。JS `Number` の安全整数範囲 (2^53 - 1 =
9_007_199_254_740_991) は西暦 287396 年まで余裕があるため、TS 側で `number` として
扱っても精度欠落しない。`BigInt` への変換を強制すると Hub / Bridge / Phone の JSON
シリアライザを統一できなくなるため避ける。

#### 2.2.2 `CurrentSessionEvent.id == null` = clear セマンティクス

`CurrentSessionEvent(id: String?)` の `null` は「現在 session 無し」(= clear) を意味
する。Glass 側受信時に null チェック 1 つで「set / clear」を分岐できる:

```kotlin
event.id?.let { repository.selectSession(it) } ?: repository.clearCurrentSession()
```

別 `current_session_cleared` event を作る代替案より wire 表面積が小さく、`@SerialName`
追加 / sealed branch 増殖を避けられる。

#### 2.2.3 `ts` のみ持つ data class の equals 衝突注意

`Hello` / `ListeningCancel` / `SessionOpen` / `SessionClose` / `Ping` は `ts` のみを
持つ data class。設計上 `ts` は単調増加なので衝突しない前提だが、Kotlin の
`equals == true` は同 `ts` で成立してしまう。将来 de-dup を入れる場合は **(kind, 受信
時刻) で判定** し、`equals` には依存しないこと。

#### 2.2.4 `ChatMessagePayload.id` の `Long` 採用

Phone-local HistoryStore の autoinc を `Long` で持つ。実運用で 2^53 (約 9 千兆) 件に
到達することは無いので TS 側で `number` として安全に扱える。HistoryStore schema は
§3.6.1 参照。

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

// §2.4.1 の `abstract val message` + lazy `get()` スタイル。
sealed class SharedWireError {
    abstract val message: String

    sealed class Connection : SharedWireError() {
        data object NotConfigured : Connection() {
            override val message: String get() = "Settings not configured"
        }
        data class ConnectFailed(val causeMessage: String?) : Connection() {
            override val message: String get() = "Connect failed: ${causeMessage ?: "unknown"}"
        }
        data object AuthFailed : Connection() {
            override val message: String get() = "Authentication failed (HTTP 401)"
        }
        data class ServerError(val httpCode: Int, val bodyHead: String) : Connection() {
            override val message: String get() = "HTTP $httpCode: $bodyHead"
        }
    }

    sealed class Permission : SharedWireError() {
        data class Aborted(val requestId: String) : Permission() {
            override val message: String get() = "Permission $requestId aborted"
        }
        data object AlreadyVerdicted : Permission() {
            override val message: String get() = "Verdict already sent or unknown request"
        }
        data class Unknown(val requestId: String) : Permission() {
            override val message: String get() = "Unknown request_id: $requestId"
        }
    }
}
```

`:protocol` に置かない error (Send / Transcription / Glass-specific) は Phone / Glass それぞれの app 内で定義 (§3.7)。

#### 2.4.1 `message` を `abstract val` + lazy `get()` で構築

各サブクラスの `message` は `override val message: String get() = "..."` のスタイルで
**毎参照で構築**する (constructor で文字列を受けない)。理由:

- 値型エラーを大量に生成する経路 (例: SSE 切断で `ConnectFailed` が連続) で、参照
  されない instance の message を毎回 allocate しない
- `data class.toString()` の挙動を予測可能にする (`data class ConnectFailed(causeMessage)`
  なら toString は `ConnectFailed(causeMessage=...)` 固定で message は別アクセス)
- idiomatic な Kotlin sealed hierarchy: `abstract val message` を継承で各 case が
  override する形

#### 2.4.2 値型エラー vs `Throwable` の使い分け

`SharedWireError` は **`Throwable` ではない**: UI / log の「材料」として渡す値型である
ことを意図する。`throw` したい経路は別途 `Exception(message)` で wrap する (= `:protocol`
の codec 失敗のみが `Throwable` を継承する `ProtocolError`、§2.8)。理由:

- 通信エラーの大半は復旧可能 (再接続 / 再 verdict) で、例外伝播より UI に状態として
  載せる方が扱いやすい
- exception を握り潰す catch ブロックを Phone/Glass に書かせない

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
        explicitNulls = false                 // §2.5.4
        classDiscriminator = "event"          // sealed 判別を "event" フィールドで
    }
    override fun encode(event: WireEvent): ByteArray =
        json.encodeToString(WireEvent.serializer(), event).toByteArray(Charsets.UTF_8)
    override fun decode(bytes: ByteArray): WireEvent? = runCatching {
        json.decodeFromString(WireEvent.serializer(), bytes.toString(Charsets.UTF_8))
    }.getOrNull()
}

interface CapsFactory {                       // §2.5.3
    fun encode(event: WireEvent): ByteArray
    fun decode(bytes: ByteArray): WireEvent?
}

class CapsCodec(private val factory: CapsFactory) : Codec {
    override fun encode(event: WireEvent): ByteArray = factory.encode(event)
    override fun decode(bytes: ByteArray): WireEvent? = factory.decode(bytes)
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

##### 2.5.1.1 `encodeToCaps` (送信側 fast-path)

decode と対称に、**送信側にも Caps 直渡し fast-path** を持たせる:

```kotlin
fun encodeToCaps(event: WireEvent): Caps   // Glass-side のみ
override fun encode(event: WireEvent): ByteArray = encodeToCaps(event).serialize()
```

Glass-side 送信経路は `CXRServiceBridge.sendMessage(channel, Caps)` を取るので、`bytes` に落としてから再 parse すると無駄が出る。`encodeToCaps` を介して `Caps` を直接返し、`GlassBridge` の送信側はそれをそのまま `sendMessage` に渡す。`encode(ByteArray)` (`CapsFactory` interface) は bytes 経路 (test / Phone-side 送信) のために残す。

#### 2.5.2 `CapsFactoryImpl` (Phone-side encoding format)

Phone → Glass の wire encoding は Rokid CXR `Caps` を **2-slot envelope** として使う:

```
Caps[0] = "json"                ← KEY_JSON (固定 string literal)
Caps[1] = "{...}"               ← WireEvent の polymorphic JSON
```

##### 2.5.2.1 envelope 構造を 2-slot に固定する理由

- 設計書が `CapsFactory` を **opaque な byte channel** とみなしているので、Caps 自体は単純な envelope で十分
- per-field のキー定義を Phone/Glass 両側で duplicate するより、`@SerialName` を持つ `WireEvent` を **1 か所で固定**するほうがスキーマ進化を 1 ソースに集約できる
- `WireEvent` は `@Serializable sealed interface` なので、コンパイラプラグインが polymorphic serializer を自動生成。手動の `SerializersModule` 登録不要

**Phone-side と Glass-side で `CapsFactoryImpl` を duplicate 実装する理由**: Phone (`phone/glass/CapsFactoryImpl.kt`) と Glass (`glass/glass/CapsFactoryImpl.kt`) は **wire 等価な実装** をそれぞれ持つ。共通化して `:protocol` に上げると `com.rokid.cxr.Caps` が両 SDK に流れて AGP の重複クラス検知に当たるため、**意図的に未抽出**で 2 箇所維持する。同一 envelope (`KEY_JSON` + WireEvent JSON) + 同一 Json 設定 (§2.5.2.2) を守る対称制約は **parity test (§2.6) で機械的に検証**する。

##### 2.5.2.2 `JsonCodec` との Json 設定整合 (P1-2)

`CapsFactoryImpl` の内部 `Json` は `JsonCodec` (SSE 経由) と**完全に同一の設定**を使う:

```kotlin
Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "event"
}
```

これにより SSE 経由 (Phone↔Hub、`JsonCodec`) と CXR 経由 (Phone↔Glass、`CapsFactoryImpl`) でペイロードが完全に同型になり、parity test (§2.6) で同じ golden を共有できる。

##### 2.5.2.3 decode 防御 (P1-3)

Rokid `Caps.Value.getString()` は型不整合時に `Caps$IncorrectTypeException` を投げる。`decode(bytes)` 全体を `runCatching { ... }.getOrNull()` でくるみ、malformed payload で binder callback (Phone-side: `GlassConnectionService.handleIncoming`) を crash させない。型/size の検査は早期 return で:

```kotlin
if (caps.size() < 2) return@runCatching null
if (keyValue.type() != Caps.Value.TYPE_STRING) return@runCatching null
if (payloadValue.type() != Caps.Value.TYPE_STRING) return@runCatching null
if (keyValue.string != KEY_JSON) return@runCatching null
```

不正 payload は `null` 戻しになり、上位 `GlassConnectionService` 側で `glass_drop_unknown_payload` warn ログ 1 行で吸収する (§3.3.3.6)。

**Glass-side `decodeFromCaps` (§2.5.1) も同根の防御**: `MsgCallback.onReceive` も binder callback なので、Caps fast-path 経路で同じ `runCatching` を維持する。Phone↔Glass どちらの受信側でも malformed payload で binder thread を死なせない契約。

#### 2.5.3 `CapsFactory` seam が同期 API である理由

`CapsFactory` interface は `:protocol` を Android-free に保つための seam (実体は Phone /
Glass 側で Rokid CXR-L SDK を呼ぶ `CapsFactoryImpl`)。**`suspend` を付けない同期 API**
にしている理由:

- CXR-L 1.0.1 では Caps の組み立て / 解析は CPU 同期処理で、I/O は別レイヤ (`sendCaps`
  等) が扱う
- encode/decode の seam に `suspend` を付けると `:protocol` の test (JVM 純粋関数想定)
  に coroutines 依存を引き込む

将来 SDK が async 化したときに `suspend` 版を追加することは可能だが、同期版は残す
(既存呼び出し側を縛らない両建て方針)。

#### 2.5.4 `JsonCodec` の `explicitNulls = false`

null フィールドを encode 時に省略 / decode 時に欠落キーを null と解釈する設定。理由:

- Hub / Bridge (TypeScript) 側で `?: undefined` を毎度書かなくて済む — Kotlin の
  `field: T? = null` ⇔ TS の `field?: T` が同じ wire 表現になる
- `:protocol` の golden (§2.6) と TS golden の文字列比較が壊れない (null 出力されない
  方が一致しやすい)
- `CapsFactoryImpl` 側の Json 設定 (§2.5.2.2) も同じ値で揃え、SSE / CXR 経路の
  ペイロード同型性を担保

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

### 2.7 `WireField` 定数

snake_case の wire フィールド名定数を集約する object。`@SerialName` と **同じ文字列**
を string constant として外出しする (`SESSION_ID = "session_id"` 等)。

#### 2.7.1 用途は「シリアライザを介さない経路」

`@Serializable` で encode/decode が自動化されているため、`JsonCodec` / `CapsCodec` を
通る経路では `WireField` を直接参照する必要はない。`WireField` が要るのは:

- **構造化ログのキー**: `tag=phone event=wire_send chat_id=... session_id=...` のように
  `parts.add("${WireField.SESSION_ID}=$id")` で打つ場合 (§8.3 の key=value 形式)
- **手書きの JsonObject lookup**: codec を通さず生の `JsonObject` を組む / 探る場合の
  型安全な key 参照 (タイポを compiler で潰す)

#### 2.7.2 `@SerialName` との文字列レベル一致が契約

`SESSION_ID = "session_id"` の右辺は `@SerialName("session_id")` と **文字レベルで
一致** させる契約。片方を rename したらもう片方も一緒に rename する。`KotlinGoldenGeneratorTest`
(§2.6) でこの一致が破られると golden が drift して fail する仕組み。

### 2.8 `ProtocolError` (codec 失敗)

`:protocol` の codec が将来 decode 失敗の詳細を呼び出し側に渡したくなったときの受け皿。
`SharedWireError` (§2.4) と違って **`Throwable` を継承** する (codec は I/O 層と同様
exception で fail-fast したい)。

#### 2.8.1 現状 dead な sealed branch を増やさない

現在 `JsonCodec.decode` は `null` を返すだけで例外を投げないため、`ProtocolError` の
`DecodeFailed` / `EncodeFailed` 経路は **dead** (実際に throw されない)。Phase 5
以降で codec が throw する経路を導入したら有効化する。

それまでは `UnknownEventType` のような具体的 sealed sub-case を追加**しない**:
未発火な catch ブロックを Phone/Glass app 側に書かせる温床になり、後から消すと breaking
change になる。発火経路ができてから sealed branch を追加する漸進的拡張方針。

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

##### 3.2.1.5 `PhoneUiState` contract

`ChannelRepository.uiState` の type。Compose Screen は **これだけ collect すれば全表示状態を再構築できる** 集約 immutable data class。

```kotlin
@Immutable
data class PhoneUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val pendingForCurrent: List<PendingPermission> = emptyList(),
    val inputText: String = "",
    val attachedImage: ImageAttachment? = null,
    val mode: ConversationMode = ConversationMode.IDLE,
    val transcriptText: String = "",
    val connectivity: ConnectivityState = ConnectivityState.Idle,
)
```

**`pendingForCurrent` を Repository 側で計算する理由 (NFR-51)**: 現在 session 宛 pending の filter を UI で `pendingPermissions.filter { it.sessionId == currentSessionId }` と書くと「UI が状態管理ロジックを持つ」NFR-51 違反になる。Repository の `combine` 段で計算した結果をフィールドとして乗せ、in-app `PermissionDialog` は **`pendingForCurrent` だけ見る** 契約にする。通知シェードや全件参照 (`pendingPermissions` 全リスト) は別フィールドで残す。

**`@Immutable` 配置の意図 (P3-A)**: `List<...>` フィールドは Compose の skippable 解析で **stable 判定が緩い**。`@Immutable` をコンテナ data class に明示することで親 recompose 時に同値引数なら子の recompose を skip できるよう Compose にヒントを与える。`SessionSummary` 等の入れ子 data class も同様に `@Immutable` を付ける。

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

##### 3.2.2.2 4xx error_code → `PhoneWireError` マッピング

Hub は 4xx 応答 body に `{"error_code": "...", "message": "..."}` を載せる契約 (§5.2.1)。`ChannelClient` は次の対応で送信側エラーに変換する:

| `error_code` | 変換先 | 用途 |
|---|---|---|
| `image_too_large` | `PhoneWireError.Send.ImageTooLarge` | UI Banner 表示 |
| `session_not_active` | `PhoneWireError.Send.SessionNotActive` | session 切替誘導 |
| その他 / parse 失敗 | `SharedWireError.Connection.ServerError(httpCode, bodyHead)` | bodyHead は先頭 200 文字 |

§5.2.1 のサーバ側エラーコード表は現在 `image_too_large` のみを `PhoneWireError` 系に明示マップしているが、Phone 側は `session_not_active` も `PhoneWireError.Send.SessionNotActive` に翻訳する (上表が source of truth)。

##### 3.2.2.3 non-ASCII token の `AuthFailed` 翻訳

OkHttp の `Request.Builder.header` は **ASCII printable (0x20-0x7E) のみ受理** し、それ以外は `IllegalArgumentException` を投げる。token に日本語等を貼り付けた場合、通常の `Failed (再試行)` 扱いになると延々と backoff loop を回すだけで UX 上原因が分からない。`ChannelClient.newRequest` で送信前に文字コード検査し、不正文字を検出したら **`SharedWireError.Connection.AuthFailed` を throw** する。`ConnectionController` の `runConnectionLoop` catch block で `WireErrorException.wireError == AuthFailed` を検出して `ConnectivityState.AuthFailed` に遷移 → SettingsDialog 自動表示経路 (§3.2.4 末尾「重要規約」) に乗せる。

##### 3.2.2.4 SSE `callbackFlow` の UNLIMITED buffer (AD-13 連動)

`events()` は `callbackFlow` の戻り値を `.buffer(capacity = Channel.UNLIMITED, onBufferOverflow = SUSPEND)` でラップする。`callbackFlow` の既定 buffer は RENDEZVOUS で、collector が遅いと `trySend` が silent drop しうる。AD-13 の `permission_snapshot` 直後に outstanding 群を **1 件ずつ順次** push する経路 (FR-HU-14、`createdAtMs` 昇順) を取りこぼさないため、無制限 buffer を明示的に挟む。

##### 3.2.2.5 cancellation 取り扱い

- `coroutineRunCatching`: 通常の `kotlin.runCatching` は `CancellationException` も catch する (kotlinx.coroutines#1814 の既知 pitfall)。suspending block 内では catch しないラッパを使い、coroutine cancel を rethrow する
- `Call.await` (OkHttp): `suspendCancellableCoroutine` で `invokeOnCancellation { call.cancel() }` を設定。scope cancel 時に socket を確実に閉じる

##### 3.2.2.6 `SseEvent` (sealed model)

Hub からの SSE と接続ライフサイクルを **1 つの sealed class に統合** した Phone-local モデル。`ConnectionController.events: SharedFlow<SseEvent>` を `ChannelRepository` が購読し、wire event は `SessionStore.applySseEvent` へ dispatch する:

```kotlin
sealed class SseEvent {
    // 接続層由来 (Hub からの wire ではない)
    data object Open : SseEvent()
    data class Failure(val message: String) : SseEvent()
    data object AuthFailed : SseEvent()
    data object Closed : SseEvent()

    // wire イベント
    data class Reply(chatId, sessionId?, text)
    data class Permission(requestId, sessionId?, toolName, description, inputPreview)
    data class PermissionAbort(requestId, reason?)
    data class SessionActive(sessionId)
    data class SessionInactive(sessionId)
    data class SessionSnapshot(activeSessionIds: List<String>)
    data class PermissionSnapshot(requestIds: List<String>)
}
```

**Wire event type → sealed のマッピング** (`ChannelClient.parseSseFrame` が source of truth):

| Wire `event:` (Hub SSE) | sealed | 用途 |
|---|---|---|
| `reply` | `Reply` | Claude からの返信 |
| `permission` | `Permission` | ツール承認要求 |
| `permission_abort` | `PermissionAbort` | 承認待ち取消 (Bridge 切断等) |
| `session_active` | `SessionActive` | session 開始通知 |
| `session_inactive` | `SessionInactive` | session 終了通知 |
| `session_snapshot` | `SessionSnapshot` | SSE 接続時 active session の reconcile (FR-HU-15) |
| `permission_snapshot` | `PermissionSnapshot` | SSE 接続時 outstanding permission の reconcile (FR-HU-14, AD-13) |

**接続層由来と wire を同一 sealed に統合する理由**: `ConnectionController` は接続状態を `Open` / `Failure` / `AuthFailed` / `Closed` で表現するが、subscriber 側 (`SessionStore.applySseEvent`) は wire を扱う。同一 sealed にすることで `_events.emit(event)` を single point に絞り、subscriber は `when (event) is SseEvent.X` の単一 dispatch で済む。

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

##### 3.2.4.1 `HubAddress` 単位の再接続判定 (P2-6)

`update(settings)` で loop を再起動する判定単位は **`HubAddress(baseUrl, token)` のペア** のみ。`openAiApiKey` 等の他フィールドが変わっても loop は再起動しない (transcription 側の責務)。`HubAddress == lastAddress` のときは早期 return で何もしない。

##### 3.2.4.2 `cancelAndJoin` で旧 loop を待つ (P1-5)

`update()` 内で `loopJob?.cancelAndJoin()` を呼ぶ。`cancel()` のみだと旧 loop の `client.events().collect` が cancel 完了する前に新 loop が起動し、`_status` / `_events` が新旧 2 つの emit 元から並行更新される race を踏む。`cancelAndJoin` で旧 loop の **完了** まで待ってから新 loop を `scope.launch` する。

##### 3.2.4.3 backoff 中の手動 reconnect で即起床 (P1-6)

`reconnectTrigger` は AuthFailed wait と Failed backoff delay の **両方** に効かせる。素朴に `delay(delayMs)` だと backoff 中の `reconnect()` 押下が次の loop 反復まで効かない。`select` で `onTimeout(delayMs)` と `reconnectTrigger.onReceive` を競合させ、`reconnect` が来たら **`attempt=0` リセット + 即再試行** に倒す:

```kotlin
val woken = select<Boolean> {
    onTimeout(delayMs) { false }
    reconnectTrigger.onReceive { true }
}
if (woken) { attempt = 0; log.info("backoff_interrupted_by_reconnect") }
```

##### 3.2.4.4 catch block での `AuthFailed` 翻訳経路

`client.events().collect` 中の例外 (= `newRequest` の non-ASCII token 検査 throw、§3.2.2.3) は SSE 経路ではなく **同期 throw** で来る。catch block で `(e as? WireErrorException)?.wireError == SharedWireError.Connection.AuthFailed` を判定して `authFailed = true` に倒し、後段の `if (authFailed)` 分岐で `ConnectivityState.AuthFailed` に遷移する。

##### 3.2.4.5 `_events` buffer policy

`events: SharedFlow<SseEvent>` は `extraBufferCapacity = 64` + `BufferOverflow.DROP_OLDEST`。subscriber が遅くて満杯になったら最も古い event を捨てる。SSE event は再接続で snapshot から再 push される (§5 / FR-HU-14) ので、新しい状態を優先する DROP_OLDEST が妥当。

##### 3.2.4.6 backoff 計算式

```kotlin
fun computeBackoffMs(attempt: Int, rng: Random = Random.Default): Long {
    val shift = (attempt - 1).coerceIn(0, 5)
    val baseMs = (1000L shl shift).coerceAtMost(30_000L)     // 1s → 32s 上限 30s
    val jitterMs = (baseMs * rng.nextDouble(-0.25, 0.25)).toLong()
    return (baseMs + jitterMs).coerceAtLeast(100L)
}
```

- `attempt=1` → 1s ±25%
- `attempt=2` → 2s ±25%
- ... 倍々で増え、`attempt=5` で 16s、**`attempt=6` 以降で 30s 上限** (shift=5 で base が 32s に達し coerceAtMost で 30s に丸まる)
- 最小 100ms (jitter 負方向で 0 に近づきすぎる事故を防ぐ)

`rng` をパラメータ化することで unit test で deterministic に判定可能。

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

##### 3.2.5.9 `TranscriptionConfig` パラメータの根拠

```kotlin
data class TranscriptionConfig(
    val apiKey: String,
    val transcriptionModel: String = "gpt-realtime-whisper",
    val sampleRateHz: Int = 24_000,
    val chunkMs: Int = 40,
)
```

- **`sampleRateHz = 24_000`**: OpenAI Realtime API の transcription pipeline の想定 sample rate と一致。これ以外だと API 側で resample されるか reject される
- **`chunkMs = 40`** (= 1920 bytes / frame @ 16-bit mono 24kHz): 実機検証で運用上の最適点。**長すぎる** と delta latency が体感できるレベルになり、**短すぎる** と WS フレーム overhead が音声 payload を上回る (40ms 未満で帯域効率が急落)
- **`transcriptionModel = "gpt-realtime-whisper"`**: realtime API の transcription pipeline 指定。将来 model 差し替えで session.update も追従する
- **mono 前提**: `frameBytes = sampleRateHz * 2 * chunkMs / 1000` は **channels=1** 固定。stereo に変える場合は係数 `2` を見直し、`MicCapture.AudioFormat.CHANNEL_IN_MONO` も同時に変更する必要がある

`init` block で `sampleRateHz > 0` / `chunkMs > 0` / `frameBytes > 0` を `require` で検証 (data class でも `init` は呼ばれる)。

##### 3.2.5.10 `EventCodec` (OpenAI Realtime API 往復)

OpenAI Realtime API (transcription-only) との JSON encode/decode 専用 internal object。

**送出 (encode)**:
- `sessionUpdate(config)`: 接続直後に **1 度だけ**送る `session.update`。sample rate / transcription model を確定
- `appendAudio(base64)`: 音声 chunk 1 つを乗せる `input_audio_buffer.append`

**受信 (decode)**: 関心ある 4 種類のみ、その他は ignore で `null` 戻し:

| OpenAI API event type | sealed | 用途 |
|---|---|---|
| `transcription_session.updated` / `session.updated` | `SessionReady` | API バージョン差を吸収するため両方許容 |
| `conversation.item.input_audio_transcription.delta` | `Delta(text)` | partial transcript |
| `conversation.item.input_audio_transcription.completed` | `Completed(text)` | 確定 transcript |
| `error` | `Error(message)` | `error.message` を抽出、無ければ event 全体を文字列化 |

**`session.created` を SessionReady としない理由**: `session.created` は接続直後の通知で **`session.update` 反映前`**。これを SessionReady とみなして音声送信を開始すると、初回 chunk が API 側で format mismatch として drop される (実機検証で確認済、§3.2.5.4 pre-buffer 設計の根拠の 1 つ)。

##### 3.2.5.11 `TranscriptionWs` (WebSocket transport)

OpenAI Realtime API への WS 接続。`TranscriptionTransport` interface を満たす internal class。

**URL / 認証**:

```kotlin
wss://api.openai.com/v1/realtime?intent=transcription
Authorization: Bearer <apiKey>
```

`intent=transcription` で transcription-only mode に固定。

**`onOpen` で `session.update` を 1 回送る**: WS 接続成功 (= `onOpen`) で `EventCodec.sessionUpdate(config)` を 1 回だけ送出。ack (= `SessionReady` event、§3.2.5.10) が返ってから初めて呼び出し側 (`TranscriptionClient`) が pre-buffer flush + 音声送信を開始する (§3.2.5.4)。

**`pingInterval = 15s` (P3-10 専用 OkHttpClient)**: OpenAI 側の idle timeout は ~60s 程度。15s 間隔で WS ping を打って keep-alive する。SSE 用の `ChannelClient.defaultClient()` (§3.2.2) とは要件が違うため **WS 専用 OkHttpClient を分離** する:

```kotlin
fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .pingInterval(15, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)   // 長時間 idle (発話間沈黙) で殺さない
    .build()
```

**Buffer policy (P2-5)**: event を運ぶ `Channel` は **bounded 256 + `DROP_LATEST`**。malicious server が message を連打しても無限 buffer に積まれない契約。通常運用 (delta / completed の頻度) では 256 件超は起こらない。

**`onFailure` の順序**: `Error → Closed` を 2 連 emit。`TranscriptionClient` の `onEvent` は `Error` で `teardownToState(State.Error)` に倒し、続く `Closed` は既に Error なので noop (§3.2.5.3)。

**`close()` で channel を閉じる**: ws を `runCatching { close(1000) }` した後、`_events.close()` で Channel も閉じる。collector 側は cancellation で teardown される (リーク防止)。

##### 3.2.5.12 `MicCapture` (AudioRecord wrapper)

24kHz mono PCM16 を `chunkMs` ms 単位で `frames: SharedFlow<ByteArray>` に流す `MicCaptureSource` 実装。

**`AudioSource.VOICE_RECOGNITION` 選択**: Android 側で **AEC / NS が薄く効く** ようにする。`MIC` source だと raw に近く noise が乗りやすい。歌の transcription はターゲットではないので music mic を避ける。

**起動失敗の早期 return + log (例外なし)**: 次の 2 経路で起動失敗を吸収:

```kotlin
if (minBuf <= 0) {                         // AudioRecord.getMinBufferSize 失敗
    log.warn("mic_min_buf_invalid"); return
}
if (rec.state != STATE_INITIALIZED) {       // AudioRecord init 失敗
    log.warn("mic_audio_record_uninit"); rec.release(); return
}
```

**例外を投げない**: 呼び出し側 (`TranscriptionClient`) は frames が一切来ないことで上位 timeout で検知する。例外を投げると `routeFrame` の collector が teardown される race を踏む。

**buffer sizing**: `bufferBytes = max(minBuf, frameBytes * 4)`。最低 4 chunk 分は AudioRecord 内部 buffer に余裕を持たせて、IO dispatcher の read 周期揺らぎを吸収する。

**IO dispatcher で blocking read**: `scope.launch(Dispatchers.IO) { while (RECORDING) { rec.read(buf, 0, size) } }`。`Job.cancel` でループ脱出 → `rec.stop` + `rec.release` を `finally` で確実に呼ぶ。

**`_frames` の buffer policy**:

```kotlin
MutableSharedFlow<ByteArray>(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)
```

`TranscriptionClient` 側の pre-buffer (§3.2.5.4) に積まれる前段なので `SUSPEND` は不要だが、**起動直後の burst** (= AudioRecord が走り出した瞬間にまとめて来る数 frame) を取りこぼさないため 64 frame (~2.5s @ 40ms) の余裕を持たせる。

**drop frame sampling log (P2-6)**: `tryEmit` が `false` を返した (= 1 frame drop された) ら `droppedFrames++` し、**10 件目ごとに 1 行 warn** を残す。silent drop は transcript 品質低下に直結するので、実機 deployment 後の field 解析で見えるようにする。

##### 3.2.5.13 `TranscriptionEvent` (正規化された wire event)

`OpenAI Realtime API` から届く JSON event を `EventCodec` (§3.2.5.10) が `TranscriptionEvent`
sealed interface に正規化:

| variant | 契機 | TranscriptionClient での扱い |
|---|---|---|
| `SessionReady` | `session.update` の ack | 受信して音声送信を本格開始 |
| `Delta(text)` | 中間 transcript (partial) | `state.Listening(partial)` に累積 |
| `Completed(text)` | 文区切りの確定 | partial をクリア、`finalized` flow へ流す |
| `Error(message)` | API 側エラー | UI に上げる + `Closed` と組で扱う |
| `Closed` | WebSocket 切断 | Idle に戻る (この後の emit は無い契約) |

`Delta` を累積した値が `TranscriptionClient.state.Listening(partial)` の `partial` で、
`Completed` が来た瞬間に partial をクリア + 確定値を `finalized: SharedFlow<String>` に
流す (`InputController` が confirm 経路に乗せる)。

##### 3.2.5.14 `TranscriptionTransport` (test seam contract)

`TranscriptionWs` (WebSocket transport) を内部 interface として抽象化し、テスト用 fake
を差し替え可能にする `internal interface TranscriptionTransport`:

| メソッド | 契約 |
|---|---|
| `events: Flow<TranscriptionEvent>` | `Closed` 到達後の emit は **無い** ことが契約 |
| `connect()` | 副作用付き、**冪等** (2 回目以降は no-op) |
| `sendAudio(base64)` | WS 未接続なら **drop** (例外を投げない、§3.2.5.7 backpressure と同じ "サイレント許容" 方針) |
| `close()` | 冪等 |

冪等性を契約に含める理由: `TranscriptionClient` のライフサイクル (§3.2.5.3) で `connect` /
`close` が再入し得る (例: SSE 再接続中に user が talk button 二度押し)。実装側で再入を
吸収させて、呼び出し側が flag を持たなくてよい設計に倒す。

#### 3.2.6 `Pairing` + `QrScanner`

Hub の `pair` CLI が表示する QR を読み取り `Settings` の接続情報 (`baseUrl` + `token`) に反映する純粋データ層パイプライン。

##### 3.2.6.1 QR payload format

Hub `hub/src/pair.ts::buildPayload` と shape を一致させる:

```json
{"v": 1, "baseUrl": "http://192.168.1.10:8788", "token": "..."}
```

- `v`: schema version。**現状 `v=1` のみ受理**し、未知 version は明示的に失敗させる (silent ignore より「app の更新が必要」と即気付かせる方が早い、§1.3 アップグレード方針)
- `openAiApiKey` は QR には **載せない** (= 個別端末で手入力)。共有 secret は `token` のみ

##### 3.2.6.2 wire key contract (P2-C)

`QrPayload` data class は **全フィールドに `@SerialName` 明示** する:

```kotlin
@Serializable
private data class QrPayload(
    @SerialName("v") val v: Int,
    @SerialName("baseUrl") val baseUrl: String? = null,
    @SerialName("token") val token: String? = null,
)
```

Kotlin プロパティ名と wire key 名を 1 箇所で**文字レベル一致**させ、Hub `pair.ts` 側との対称性を担保する。プロパティ rename しても wire key は動かない契約。

##### 3.2.6.3 null / blank 正規化 (P1-A, P1-B)

Hub の guard を通過していても、JSON 上で `null` literal や空文字が来る可能性 (race / 手動 QR) を `Pairing.parse` 側で吸収する:

- `QrPayload.baseUrl` / `.token` は **nullable で受ける**
- `require(!baseUrl.isNullOrBlank())` / `require(!token.isNullOrBlank())` で統一エラーに正規化
- すべて `require` (`IllegalArgumentException`) に揃え、`error` (`IllegalStateException`) と混ぜない (将来 catch by type 分岐に強い、P1-B)

`parse(raw: String)` は `Result<PairingResult>` を返し、`PairingResult.mergeInto(current: Settings)` で **接続情報だけ** を既存 Settings にマージする (`openAiApiKey` 等は保持)。

`Pairing` は Android API に触らない pure object なので **JVM 単体テストで網羅**。

##### 3.2.6.4 `QrScanner` の cancel 取り扱い (P2-D, P2-E)

`GmsBarcodeScanning.startScan` を `suspendCancellableCoroutine` で包むだけの thin wrapper。`addOnCanceledListener` から `QrScanCancelled extends RuntimeException` を投げる:

- **`CancellationException` 継承にしない**: structured concurrency が「親 scope 自体が cancel された」と見なし、SettingsDialog 側のエラー枝を通らなくなる。ユーザの能動キャンセルは `RuntimeException` 系として `runCatching` で `Result.failure` に入れ、UI 側で `is QrScanCancelled` 判定で抑制する
- **ML Kit は外部から閉じる API を提供しない**: coroutine cancel が来ても scanner Activity を dismiss する手段は無い。`invokeOnCancellation` は登録するが no-op で、後続の Success/Cancel listener の `cont.isActive` チェックで二重 resume を防ぐ
- **テスト不在**: 中身は GMS / ML Kit への薄いラップ。Robolectric / GMS mock は coverage 比でコスト過大。`Pairing.parse` 側の pure unit test で payload 解析を完全に固める方針

#### 3.2.7 `ImageProcessor`

画像添付の取り込みパイプライン。Picker から来た `Uri` を Phone-local cache に複製して `ImageAttachment` を返す。

##### 3.2.7.1 cache-first 戦略

base64 化は send 直前 (Bridge への POST 段階) に行う設計なので、`ImageProcessor` は **cache file への複製 + メタデータ採取** までに責務を絞る:

```kotlin
val cacheFile = File(context.cacheDir, "attach-${UUID.randomUUID()}.bin")
resolver.openInputStream(uri)?.use { input ->
    cacheFile.outputStream().use { output -> input.copyTo(output) }
}
```

長期保管は path のみで済むので、Repository のメモリプレッシャ・recomposition コストを抑える。

##### 3.2.7.2 サイズ上限 11MB の根拠

```kotlin
private const val MAX_BYTES: Long = 11L * 1024 * 1024
```

Hub 側 `BODY_LIMIT_BYTES = 16MB` (image_base64 込みの POST body)。base64 化で 4/3 倍 (≒ 21.3%) 膨らむ + JSON フレーム overhead を見込んで **raw 上限は 11MB** に決定 (11 × 4/3 ≒ 14.7MB < 16MB)。Picker 受け取り時点で fail-fast させ、Hub に大容量 upload を投げてから 413 で叩き落とされる UX を避ける。

##### 3.2.7.3 解像度測定: `inJustDecodeBounds`

```kotlin
val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
BitmapFactory.decodeFile(cacheFile.absolutePath, opts)
val longest = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(0)
```

`inJustDecodeBounds = true` は **pixel data を allocate せず幅高さだけ取得** する。`ImageAttachment.longestEdge` は UI 側 thumbnail の生成 hint に使う。

##### 3.2.7.4 失敗時の cache 即削除 + typed wire error (P2-E, P2-F)

すべての失敗経路で cache file を即 delete (cacheDir に残しっぱなしを防ぐ):

```kotlin
try { ... } catch (t: Throwable) {
    runCatching { cacheFile.delete() }
    throw t
}
```

`IllegalArgumentException` の生 `message` を UI に流すと i18n / フォーマットが既存経路と一貫しないため、**`PhoneWireError.Send.ImageTooLarge.asException()` の typed wire 例外** で投げる。Repository 側の `emitErrorFromThrowable` が `errors` flow に流し、`MainScreenEffects.toUserMessage` がローカライズされた snackbar 文字列を生成する経路に乗る (§3.7)。

##### 3.2.7.5 意図的に省略している機能

現スコープでは不要として落とす:

- **自動リサイズ / 再エンコード**: Bridge 側で行う (Phone は raw を渡すだけ)
- **EXIF 回転補正**: Compose の画像表示側で吸収
- **HEIC 変換**: Android picker がデコード可能な形式に依存 (新しい端末はだいたい OK)

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

**役割**: `AudioRecord` を OS から確保し続けるために `FOREGROUND_SERVICE_TYPE_MICROPHONE` の通知を立てておくだけ。実際の `AudioRecord` 駆動は `InputController` 経由の `MicCapture` が行う。

##### 3.3.4.1 onCreate の eligibility 二重ガード (defensive)

§3.3.6.4 で説明した RealFgsOperations 側 eligibility 判定 (第一防衛線) に加えて、`MicForegroundService.onCreate` でも同じ判定を行う **第二防衛線**:

```kotlin
if (!RECORD_AUDIO granted) { stopSelf(); return }
if (!ProcessLifecycleOwner is STARTED) { stopSelf(); return }
startForegroundCompat()
```

第二防衛線が必要な edge case:
- **OS triggered restart**: `START_NOT_STICKY` を返しているが、OS が他経路で Service を立ち上げてしまう可能性
- **mid-session の権限 revoke**: 録音中にユーザが Settings から権限を取り消す
- **dispatcher 側のバグ**: 第一防衛線が将来 regression したときの fallback

不適合なら `stopSelf()` で即停止し、`startForeground(MICROPHONE)` を呼ばないことで `SecurityException` / `ForegroundServiceDidNotStartInTimeException` を回避する。

##### 3.3.4.2 `onStartCommand = START_NOT_STICKY`

`ChannelService` / `GlassConnectionService` と違い、`MicForegroundService` は **OS kill 後 redeliver しない**。Mic FGS は Glass session の伴走 lifecycle (= `Stopping` で必ず stop される) なので、独立に再起動すると state machine の前提が崩れる (§3.3.1)。AppLifecycleController から明示的に再 start させる経路に倒す。

対比:
- `ChannelService` (`START_STICKY`): SSE 接続を OS に委ねて長命に維持。再起動経路でも Repository / 通知の再 init は idempotent (§3.3.2.2)
- `GlassConnectionService` (`START_STICKY`): CXR-L token を `TokenStore` から再読込して接続復帰 (§3.3.3.7)
- `MicForegroundService` (`START_NOT_STICKY`): 独立再起動の意味が無い (Glass session 不在で mic を確保しても無駄)。AppLifecycleController が deterministic に再 start する

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

##### 3.3.5.4 実装上の不変条件

- **専用 OkHttpClient (callTimeout=4s)**: `ChannelClient.defaultClient()` は SSE 用に `readTimeout=0` なので、verdict 送信には `callTimeout=4s` / `connectTimeout=2s` の **別 OkHttpClient を都度建てる**。LAN/Tailscale 不通時の deadlock を 4s で打ち切ることで NFR-14 (5s) 予算内に収める
- **二度叩き対策**: 同一 PendingIntent で `onStartCommand` が複数回呼ばれる theoretical race を、`inflight?.cancelAndJoin()` を `runBlocking { withContext(NonCancellable) { ... } }` で待ってから新 launch する形で潰す (Service main thread を ms オーダー block するだけで ANR には至らない)
- **`parseDecision` の防御 (P3-6)**: 設計書は `PermissionDecision.valueOf(behaviorStr)` を呼ぶ snippet だが、本実装は **例外を起こさない `when` 比較**に置き換える。`Receiver` が enum 名以外を渡す経路は理論上ないが、verdict 経路は extras 破損で crash させたくない (kill 中の通知応答 = ユーザの唯一の操作経路)
- **missing extras の早期 stop**: `requestId` / `behavior` / `baseUrl` / `token` のいずれかが blank なら warn log + `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()` で即終了
- **`AuthFailed` 時の通知文言書き換え hook**: 現状は warn log のみ。「再ペアが必要」への文言書き換えは 4c+ で `updateNotificationForAuthFail` を実装する設計 (§3.7 と整合)

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

#### 3.4.3 `BtAudioRouter` (Bluetooth SCO 経由の Glass mic)

`InputController.AudioRouter` 実装。Rokid Glass は BT 通話デバイスとして見えるので、Phone の Communication Device をこれに切り替えると `AudioRecord` が自然と Glass mic を読むようになる (CXR-L は不要)。`routeToGlassMic()` / `restore()` は **対で呼ぶ契約**で、`InputController` が責任を持つ。

##### 3.4.3.1 切替シーケンス

```
routeToGlassMic():
  savedMode = am.mode
  am.mode = MODE_IN_COMMUNICATION
  bt = am.firstBtCommDevice()          ← runCatching で SecurityException も吸収
  if (bt == null) { am.mode = savedMode; return false }
  ok = am.setCommunicationDevice(bt)   ← runCatching
  if (!ok) am.mode = savedMode
  return ok

restore():
  if (routed) am.clearCommunicationDevice()
  am.mode = savedMode
```

失敗時 (BT device 不在 / `SecurityException` / 権限不足) は `false` を返し、呼び出し側で **PHONE_FALLBACK + `BtScoUnavailable` error emit** に倒す (§3.2.5.6 と整合)。

##### 3.4.3.2 `BLUETOOTH_CONNECT` runtime perm (P3-5)

API 31+ で **`BLUETOOTH_CONNECT` は runtime permission**。Manifest だけでは足りず、実行時に granted を確認するか `SecurityException` を全 BT path で catch する必要がある。本実装は **後者を採用**: BT 周りの全ての操作 (`firstBtCommDevice` / `setCommunicationDevice` / `clearCommunicationDevice`) を `runCatching` で囲い、`SecurityException` が来ても false 戻しで PHONE_FALLBACK に倒す。

##### 3.4.3.3 既知の race timing 制限 (P2-6)

`AudioManager.setCommunicationDevice` は **即時 boolean を返すが、BT SCO の実際のリンク確立は非同期** (~数百 ms)。短時間で `routeToGlassMic → restore` を連打すると、`clearCommunicationDevice` がリンク確立前に呼ばれ no-op になりうる。本実装では **`InputController` が transcription 起動から少なくとも数百 ms は録音を続ける契約** に依存して race を回避している。厳密に同期したい場合は `addOnCommunicationDeviceChangedListener` で gating する (将来検討項目)。

##### 3.4.3.4 `AudioManagerLike` 抽象化 (#183)

Phase 5 で `AudioManagerLike` interface を introduce し、production は `AndroidAudioManagerLike.fromContext(...)`、test は fake AudioManager で BT 不在ケース / route 失敗ケース / SecurityException ケースの 3 fallback path を unit test で押さえる。`BtAudioRouter internal constructor(am: AudioManagerLike?)` が seam。

##### 3.4.3.5 `BtCommDeviceRef` value-object wrapping

`AudioDeviceInfo` (Android 固有型) を `BtAudioRouter` に直接露出させない。`BtCommDeviceRef` 抽象 (`type: Int` / `productName: String` のみ) で wrap し、router は `firstBtCommDevice()` から得たそれをそのまま `setCommunicationDevice(ref)` に渡す:

```kotlin
internal interface BtCommDeviceRef {
    val type: Int
    val productName: String
}

internal class AndroidBtCommDeviceRef(val device: AudioDeviceInfo) : BtCommDeviceRef {
    override val type = device.type
    override val productName = runCatching { device.productName?.toString().orEmpty() }
        .getOrDefault("")
}
```

これで test 側は Android SDK 依存無しで全 fallback 経路を assert できる。`productName` の `runCatching` は一部端末で `AudioDeviceInfo.productName` が `SecurityException` を投げるケースの防御。

`context.getSystemService(AudioManager::class.java)` が null を返した場合は `fromContext` が null を返し、`BtAudioRouter` 側で `audio_router_no_audio_manager` warn + `false` 戻しに倒す。

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

##### 3.5.1.1 `GlassDialog` (Glass 接続管理 UI)

CXR-L 認可 → 接続 → 切断のライフサイクル UI。3 状態で confirm ボタンが分岐する:

| 状態 | confirm ボタン | dismiss ボタン |
|---|---|---|
| `TokenStore.token == null` | Hi Rokid 認可ボタン | 閉じる |
| token あり / 未接続 | 接続 (= `startGlassSession`) | 認可を解除 |
| 接続中 | 切断 (= `stopGlassSession`) | 認可を解除 |

**接続表示 label の真実値は `lifecycle.glassState`** (P2-G): `GlassConnectionService.connState` は CXR-L レベルの補助情報として「接続済み / 接続中…」の差を出すためだけに利用。`Off` / `Stopping` / `Starting` のときは `cxrState` を無視する (lifecycle と CXR-L が並行に動くため、片方しか見ないと state machine と表示が乖離する)。

**接続ボタン押下時の RECORD_AUDIO runtime permission**: Android 14+ で Mic FGS-microphone は **`RECORD_AUDIO` 取得済が必須** (§3.3.4.1)。ボタン押下時に未取得なら `rememberLauncherForActivityResult` で runtime permission を要求し、grant 後に `startGlassSession` を呼ぶ。拒否時は何もしない (Settings から再許可後に再押下する経路に倒す)。

**「認可を解除」の操作順序 (P3-F)**: 次の順序を**入れ替えない**:
1. running なら `stopGlassSession` を投げる (suspend → lifecycle が Stopping → Off)
2. **完了を待たずに** `TokenStore.clear` を即同期で実行

token を先に clear すると、stopGlassSession 中に GlassRelay が token を要求するパスで `TokenMissing` が `_errors` に流れ「解除中なのにエラー」とユーザに見える。stop fire-and-forget → clear sync で「stop が in-flight でも token はもう無い」状態にし、残作業は lifecycle の watchdog で畳む。

##### 3.5.1.2 `SettingsDialog` (Hub 接続設定 + API key)

QR pairing + 手動入力で `Settings` を編集。

**QR スキャン経路**: `QrScanner.scan(context)` → `Pairing.parse(raw)` (§3.2.6) → `baseUrl` / `token` フィールドにセット。`openAiApiKey` は端末固有 secret なので QR には乗らず手入力のまま保持。

**スキャン / 解釈エラーの表示**: dialog 内 inline text (`AlertDialog` の中で snackbar は出せない)。`scanError` は `rememberSaveable` で config change (回転等) を超えて保持 (P3-E)。`QrScanCancelled` (= ユーザの能動キャンセル) は error 扱いせず、UX noise を排除 (§3.2.6.4)。

**token 入力時の即時 validation (#189)**: OkHttp の HTTP header value は **ASCII printable (0x20-0x7E)** のみ受理 (§3.2.2.3)。送信時の `AuthFailed` 翻訳経路は機能するが、`SettingsDialog` で **入力段階で弾く** ことで Hub round-trip を省く UX 改善:

```kotlin
val tokenError = remember(token) {
    val invalidIdx = token.indexOfFirst { c -> c.code < 0x20 || c.code > 0x7E }
    if (invalidIdx >= 0) "ASCII 印字可能文字 (0x20-0x7E) のみ..." else null
}
// 保存ボタンは tokenError != null のとき disabled
```

**保存時の正規化**: `baseUrl.trimEnd('/')` で末尾 slash を取り除いてから保存 (`Pairing.parse` と同じ正規化)。

##### 3.5.1.3 Phone `MainActivity` (Activity host)

Phone app の唯一の `Activity` (`singleTask` で 2 つ目のインスタンスは作らない)。責務:

- Compose UI (`MainScreen`) の host
- 通知タップ → `onNewIntent` で extras を読んで該当 session に切替 (§3.6.4)
- Hi Rokid 認可結果 → `onActivityResult` で `TokenStore.save`
- `POST_NOTIFICATIONS` runtime permission の取得 (Android 13+)

**通知連打 race 回避 (P2-B)**: 旧版は `LaunchedEffect(pendingSessionId) { viewModel.selectSession(target) }` を使っていたが、通知連打で `pendingSessionId` が上書きされた場合、**前の `select` が in-flight のまま新しい `select` と race** する。現行は `snapshotFlow + collectLatest` セマンティクスにして「新しい値が来たら前の select を cancel」に倒す:

```kotlin
LaunchedEffect(Unit) {
    snapshotFlow { pendingSessionId }
        .collectLatest { target ->
            if (target != null) {
                viewModel.selectSession(target)
                pendingSessionId = null   // 1 回消費
            }
        }
}
```

`onCreate` の `intent.extractSessionId()` で **kill 状態から通知タップ起動された経路** の初回 `pendingSessionId` も拾う。

**`POST_NOTIFICATIONS` (Android 13+)**: 未許可だと OS が reply/permission 通知を silent drop するため、app 起動時に 1 回 `RequestPermission` を出す。callback は log のみで拒否時の retry は user の Settings 経由に任せる。

**`onActivityResult` deprecation の取り扱い (P2-C)**: `AuthorizationHelper.requestAuthorization` が legacy `startActivityForResult` API しか提供しないため、modern `ActivityResultContracts.StartActivityForResult` への移行は SDK 側が許す時まで待つ。現状は `@Deprecated` + `@Suppress("DEPRECATION")` で抑制。

##### 3.5.1.4 `MainScreenScaffold` の細部設計

TopAppBar + ModalNavigationDrawer + MessageList + InputBar を組み立てる Scaffold composable。

**IME 追従の二重補正回避**: `Modifier.imePadding()` が IME 追従を担当し、Activity 側は `AndroidManifest.xml` で **`windowSoftInputMode="adjustNothing"`** を設定して system の window resize を抑制する。Android 15+ の `adjustResize` 強制と `.imePadding()` の二重補正が起きると、入力欄の下に不自然な隙間が出る (regression を実機で確認済)。**この 2 つはペアで動く設計** なので、片方だけ外すと再発する。

**photo picker + RECORD_AUDIO permission の経路**:

```kotlin
val photoPicker = rememberLauncherForActivityResult(
    ActivityResultContracts.PickVisualMedia(),
) { uri -> if (uri != null) viewModel.attachImageFromUri(uri) }   // → Repository (§3.5.2.3)

val micPermission = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    if (granted) viewModel.startTranscriptionPhoneMic()
    else scope.launch { snackbar.showSnackbar("マイクの権限が必要です") }
}
```

**MessageList の recompose 最適化 (P2-A)**: parent Scaffold は `ui` の他フィールド変化でも recompose されるが、`MessageList` は `messages: List<ChatMessage>` だけを受け取り、`LazyColumn` の `key = { it.id }` で item-level recompose を最小化する。`PhoneUiState.@Immutable` (P3-A) + reference-equal `messages` で Compose の skip も働く (§3.2.1.5)。

**TopAppBar title fallback (P3-B)**: `currentSessionTitle` は `SessionSummary.label` を優先し、session が一覧に居ない場合 (= `UNKNOWN_SESSION_ID` で active 化前) のみ `id.take(8)` (shortSessionLabel) に fallback する。Drawer 側と同じ表記を保つことで、ユーザが drawer ↔ TopAppBar 間で session を identify できる契約。

**send の click-time snapshot (P1-C)**: `onSend = { viewModel.send(inputText) }` は click 時の `inputText` を即座に引数として渡す (§3.5.2.2 と同じ理由 — IME composition / transcription partial の race を避ける)。

##### 3.5.1.5 `MainScreenDialogState` (dialog / drawer / snackbar 状態 holder)

`@Stable class` で dialog open/close + drawer + snackbar の transient state を保持。
`viewModel.uiState` のような repo 由来の state は **含めない** (Compose recomposition
の skip 境界を綺麗にする目的、§3.5.3)。

- `showSettings` / `showGlass` / `showExit`: AlertDialog の表示 flag
- `pendingDeleteSessionId: String?`: 削除確認 dialog の対象 (`null` = 閉じている)
- `snackbar` / `drawer`: `SnackbarHostState` / `DrawerState` を持つ

**P1-A 4c2 review**: dialog **自動表示** の判定 (Idle / AuthFailed → settings) は
`MainScreenEffects` (§3.5.1.7) に集約し、ここでは state container のみ提供する。
`MainScreenDialogState` は connectivity 引数を取らず、再評価ロジックを持たない。

##### 3.5.1.6 `MainScreen` 最上位 composable (AD-18 の 3+1 分解 wiring)

`MainScreen()` は `ChatViewModel` から `uiState` / `settings` / `connectivity` /
`transcriptionState` / `inputText` を `collectAsStateWithLifecycle` で取り出し、
**3 個の sibling composable + 1 個の state holder** に分配する:

| sibling | 役割 |
|---|---|
| `MainScreenScaffold` | TopAppBar + Drawer + MessageList + InputBar (§3.5.1.4) |
| `MainScreenDialogs` | Settings / Glass / Exit / Delete 確認の AlertDialog 群 |
| `MainScreenEffects` | LaunchedEffect 群 (§3.5.1.7) |
| `MainScreenDialogState` (holder) | dialog flag を 3 sibling で共有 |

巨大な単一 composable では recomposition の粒度が粗く、無関係な state 変更でも再描画
する。AD-18 (§3.5.3) の方針で粒度を細かく刻み、各 sibling は **自分が必要とする state
だけ受け取る** (= 他フィールド変化で再描画されない)。

##### 3.5.1.7 `MainScreenEffects` (LaunchedEffect 集約)

3 つの副作用を集約:

1. **connectivity 観測 → settings 自動表示 (P1-A)**: `LaunchedEffect(connectivity)` で
   `Idle` / `AuthFailed` を観測したら `showSettings = true`。`connectivity` を key に
   することで状態変化のたびに再評価する (旧設計の `LaunchedEffect(Unit)` 一度限り
   発火だと、起動後に状態が変わったケースをカバーできなかった)
2. **transcription error → snackbar**: `LaunchedEffect(transcriptionState)` で
   `Error(message)` を observe したら snackbar 表示
3. **`Repository.errors` → snackbar**: `LaunchedEffect(Unit)` で
   `viewModel.errors.collect { ... }` し、`toUserMessage()` で localized text に変換
   して snackbar 表示

**`toUserMessage()` の責務分担**: `TransientError` を 1 行 message に翻訳する
private 関数。`SharedWireError` / `PhoneWireError` の各 sub-case に対応する
localized 文字列を 1 箇所に集約する (詳細な UI 表現分岐は §3.7 `mapToPresentation`
側に置く想定で、ここは snackbar 1 行に縮約する単純化版)。

##### 3.5.1.8 `ExitDialog` の `shutdownAll → finishAndRemoveTask` 順序 (P2-H)

「Hub への接続と常駐通知を停止してアプリを終了します」を確認する AlertDialog。OK 時は
**必ず以下の順序**で操作する:

```kotlin
scope.launch {
    app.container.lifecycle.shutdownAll(context)       // 1. suspend で全 FGS を await 停止
    context.findActivity()?.finishAndRemoveTask()      // 2. Activity を task ごと dispose
}
```

逆順 (Activity を先に閉じる → FGS が START_STICKY で OS から自動再起動) を踏むと、
**Activity が消えた直後に FGS stop 中の状態で OS が再起動を仕掛けに来る race** が
発生する。`shutdownAll` の中で各 FGS の `stopForeground` / `stopSelf` を await する
ことで、`finishAndRemoveTask` の時点でプロセスに残作業が無いことを保証する (P2-H
の race 解消経路、§3.3.6 lifecycle 集約と対)。

##### 3.5.1.9 `MainScreenDialogs` (dialog 分岐集約)

5 種類の dialog を if-then で組み立てる小 composable: `SettingsDialog` /
`GlassDialog` / `ExitDialog` / `PermissionDialog` / `DeleteSessionDialog`。
Scaffold (`MainScreenScaffold`) と分離する理由は、dialog の open/close state 変化
で Scaffold ツリーを recompose させないため (AD-18 の粒度方針、§3.5.3)。

**P1-6 (AC-05): pendingForCurrent filter**: 表示する permission は **現在の session の
分だけ**。別 session の permission は通知シェードの Allow/Deny で処理できるので画面
遷移を奪わない (Glass 側のフィルタと対称)。filter は `Repository.combine` で計算済み
の `ui.pendingForCurrent: List<PendingPermission>` を読むだけ。

**P1-B 4c2 review: `responded` 連打防止 gate**: `respondPermission` は suspend で
HTTP request + `_pendingPermissions` 更新まで時間がかかるため、完了して dialog が
閉じるまで「もう一度押せる」窓ができる。連打すると Hub に重複 verdict が飛んで
`PERMISSION_GONE` snackbar の連鎖になる。対策として **当該 `request_id` ごとに**
`var responded by remember(pending.requestId) { mutableStateOf(false) }` で gate
する (= `request_id` キーなので別 request が来たら自動でリセット)。

##### 3.5.1.10 `phone/ui/util/` 3 ファイル

UI 層の小ユーティリティ。それぞれ単機能で「型契約 + 使い所」を 1 行 KDoc に縮約。

| ファイル | 関数 | 用途 |
|---|---|---|
| `Format.kt` | `shortSessionLabel(id)` | UUID 先頭 8 文字。`UNKNOWN_SESSION_ID` sentinel (FR-PH-55) は `"unknown"` に明示変換 |
| `ContextExt.kt` | `Context.findActivity()` | Compose `LocalContext.current` → `Activity` 抽出。`ContextWrapper` チェーンを `baseContext` で辿る |
| `ImageBitmaps.kt` | `rememberImageBitmapFromPath(path)` | `BitmapFactory.decodeFile` を `remember(path)` で Composition cache。recomposition のたびの decode 再実行 (CPU + memory コスト大) を防ぐ目的 |

**`findActivity` の `ContextWrapper` チェーン辿り**: Activity は `ContextThemeWrapper`
→ `Activity` のように入れ子になっていることがあるため、単純な `as? Activity` cast
では nullable で取れない。`baseContext` を while loop で辿り、最初に当たった
`Activity` を返す。

**`rememberImageBitmapFromPath` の null / 失敗扱い**: path が null / blank なら
null を返す。decode 失敗 (file が消えた、format 不正) は `runCatching` で吸収して
null。caller (`InputBar` の attach chip など) は null で「画像プレビュー無し」UI を
出す契約。

##### 3.5.1.11 `MessageList` + `MessageRow` (displayText fallback)

会話メッセージ一覧の Compose composable。AD-18 (§3.5.3) で **`messages:
List<ChatMessage>` だけ受け取る**: 他フィールド変化で recompose されないよう
state を絞る + `LazyColumn(key = { it.id })` で item diff を最小化する。

**自動末尾スクロール**: `LaunchedEffect(messages.size)` で `state.animateScrollToItem(
messages.size - 1)`。最新メッセージ追加で末尾に追従。

**P3-D 4c2 review: displayText の 3 段 fallback**: `MessageRow` の中身は bubble
描画判断を以下の優先順位で:

| 条件 | 描画 |
|---|---|
| `text.isNotBlank()` | 通常の text bubble |
| `image != null && bitmap == null` | `"(画像)"` テキスト fallback |
| それ以外 (text 空 + image 無し / bitmap 描画済) | bubble を出さない (空白) |

旧条件 `text.isNotBlank() || image == null` だと「text 空 + image 無し」で空 bubble
に `"(画像)"` だけ出るバグがあった (image を消した古い msg / SYSTEM 系の空 text)。
**画像 path はあるが bitmap load 失敗時** にはユーザに「(画像)」と何かを見せる目的で
fallback 1 つだけ残す。

##### 3.5.1.12 `ConnectionLine` (TopBar 下の 1 行 status)

`ConnectivityState` を色 + 短いラベルの 1 行に映す。`Idle / Connecting / Open /
Failed(reason) / AuthFailed` の 5 状態に対応:

| 状態 | label | colorScheme |
|---|---|---|
| `Idle` | 未設定 | outline |
| `Connecting` | 接続中... | tertiary |
| `Open` | 接続済み | primary |
| `Failed(reason)` | 失敗: {reason} | error |
| `AuthFailed` | token 無効 | error |

8 dp の色付き丸 + label を Row で並べる minimal な status indicator。`AuthFailed` を
`Failed` と別色 / 別 label にしないのは設計判断 (どちらも attention を引きたいので
error 色で揃える、対応は §3.6.5.5 で UI gate される)。

#### 3.5.2 `ChatViewModel`

```kotlin
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChannelRepository =
        (application as PhoneApplication).container.repository
    val uiState: StateFlow<PhoneUiState> = repository.uiState
    val settings: StateFlow<Settings> = repository.settings
    val connectivity: StateFlow<ConnectivityState> = repository.connectivity
    val inputText: StateFlow<String> = repository.inputText
    val transcriptionState: StateFlow<TranscriptionClient.State> = repository.input.transcription.state
    val errors: SharedFlow<TransientError> = repository.errors
    val events: SharedFlow<ChannelEvent> = repository.events
    // ...
}
```

##### 3.5.2.1 設計契約

- **薄い委譲層**: すべての suspend action (`send` / `selectSession` / `saveSettings` 等) は `viewModelScope.launch` で wrap し、UI 側は fire-and-forget で呼べる
- **Repository 取得経路**: `(application as PhoneApplication).container.repository`。`AppContainer` 初期化前に ViewModel が作られる経路は無い (MainActivity が Application 起動を経由する)
- **同期 getter**: 純粋な state mutation (`attachImage` / `clearAttachedImage` / `updateInputText`) は suspend 不要なので非 launch で直接委譲

##### 3.5.2.2 `send` 引数の snapshot 化 (P1-C)

`send(text)` を呼ぶ際は **呼び出し側で snapshot 化したテキストを引数で渡す**。`repository.send(inputText.value)` 形にすると次の race を踏む:

1. ユーザがボタン click
2. ViewModel.send 呼ばれる
3. `viewModelScope.launch { ... }` 起動 (まだ実行されていない)
4. その間に IME composition 遅延入力 / transcription partial 更新で `inputText` が変化
5. launch 実行時に変わった `inputText.value` を読んで送信

ボタン click 時点で UI 側が `text` を確定させて引数経由で渡すことで、launch 起動の隙間に割り込まれない契約にする。

##### 3.5.2.3 `attachImageFromUri` の Repository 集約 (P2-E)

Picker から来た `Uri` の取込を ViewModel ではなく **Repository に集約**する:

```kotlin
fun attachImageFromUri(uri: Uri) {
    viewModelScope.launch { repository.attachImageFromUri(uri) }
}
```

失敗時は `Repository._errors` 経由で `MainScreenEffects.toUserMessage` の localized snackbar 経路を通る (§3.7)。ViewModel 側で `ImageProcessor.encode` 直呼びにすると error 翻訳が UI 層に漏れる。

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

##### 3.6.1.1 serializer + 順序保持

```kotlin
private val serializer = MapSerializer(
    String.serializer(),
    ListSerializer(ChatMessage.serializer()),
)
```

`load` の戻り値は `LinkedHashMap` に変換することで **session 順序を JSON ファイル上の順序のまま保持** する (`mapValuesTo(LinkedHashMap()) { ... }`)。SessionStore 側はこの順序を SessionDrawer の表示順に流用する。

##### 3.6.1.2 `load` の壊れた状態の段階的 recovery

`load()` 冒頭で次の優先順位で処理:

1. **`filesDir.mkdirs()`** — 初回起動 / Robolectric 環境でも親 dir 不在で落ちないよう先に作成
2. **`recoverIfTmpOrphaned()`** — `.tmp` のみ残っていれば前回 crash と判定して target にリネーム。target も tmp も両方ある場合は `lastModified()` 比較で新しい方を採用 (古い方は `*.bak.<ms>` にバックアップ、P2-8 で `REPLACE_EXISTING` を付ける)
3. **`targetFile.exists()` false** → 空 map
4. **`readText()` `IOException`** → error log + 空 map
5. **`raw.isBlank()`** → 空 map (truncated write などの fallback)
6. **JSON parse 失敗** → 当該 file を `*.corrupt.<ms>` にバックアップ + 空 map で再開

すべての fail-safe で空 map に倒すことで、UI が `load` 後にクラッシュしない契約を満たす。

##### 3.6.1.3 `save` の atomic move 失敗パス

`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` が `AtomicMoveNotSupportedException` (異 filesystem 跨ぎ等) または `IOException` を投げた場合、**`.tmp` は削除しない**。次回 `load()` の `recoverIfTmpOrphaned` で:

- target が古い (or 不在) なら tmp を promote
- target が新しいなら tmp を破棄

ことで save の eventual consistency を担保する。

#### 3.6.2 設定 DataStore (+ 暗号化 token storage)

Phone-local の設定永続化は **2 系統**: DataStore (非機密の Settings) と `EncryptedSharedPreferences` (CXR-L token、§3.6.2.1)。

```
Preferences keys:
- "base_url" (String)
- "token" (String)
- "openai_api_key" (String)
- "last_current_session_id" (String, FR-PH-54)
```

##### 3.6.2.1 `TokenStore` (CXR-L token secure storage)

Settings DataStore (`base_url` / `token` / `openai_api_key`) とは **別の secure storage** として CXR-L pairing token を `EncryptedSharedPreferences` で持つ:

```
prefs file: claude_mhud_secure_prefs
key:        cxr_token
backend:    EncryptedSharedPreferences (AES256-GCM value / AES256-SIV key)
```

Settings DataStore と分ける理由:
- **役割が違う**: `TokenStore` の token は **Rokid CXR-L SDK の credential** (Glass 接続用)。Hub の `X-Token` (HTTP) とは別物
- **at-rest 暗号化を強める**: NFR-20 と同等扱い。Hub token は HTTP の `X-Token` だけで OS の保護に頼るが、CXR-L token は端末ローカルで `EncryptedSharedPreferences` を経由する

##### 3.6.2.2 `TokenStore` 実装上の不変条件

- **`prefs()` の lazy cache (P3-1)**: `EncryptedSharedPreferences.create` は MasterKey 派生 (KMS) を毎回叩くため **ms オーダーで重い**。`@Volatile cached: SharedPreferences?` + `synchronized` で application scope に単一インスタンスを保持
- **`load(context)` の例外吸収**: MasterKey 異常 / 暗号鍵ローテ後のリストア等で `getString` が例外を投げるケースがあるため、`try { ... } catch (e: Throwable) { warn log; null fallback }` で起動を止めない
- **`save` / `clear` の StateFlow 同期更新**: `prefs().edit { ... }` の直後に `_token.value = newValue` (or `null`)。observer (`GlassConnectionService.onStartCommand` 等) が最新値を即読む契約
- **`load(context)` は `Application.onCreate` で 1 回だけ同期実行**: 以降の read は flow 経由で済むので lazy init は取らない (起動順は §3.3.6.1 ステップ 1)

##### 3.6.2.3 `SettingsStore` の実装選択 (DataStore + 平文 v1.0)

| 項目 | 採用 |
|---|---|
| backend | `androidx.datastore.preferences.preferencesDataStore` |
| file 名 | `claude_mobile_hud_settings` |
| token 暗号化 | **v1.0 では平文** (将来 `androidx.security.crypto` 移行を検討) |

**v1.0 で `Hub token` を平文 Preferences に置く理由**: NFR-20 の脅威モデルは
LAN / Tailscale の中間者 + 公衆 LAN での盗聴を主敵とする。**Phone 端末自体が compromise
された状況は scope 外** にしているため、at-rest 暗号化は Hub token に対して優先度を
下げる (CXR-L token は §3.6.2.1 で別に `EncryptedSharedPreferences` を使う = 役割が
違うため別判断)。将来端末紛失モデルを脅威に組み込むときに `crypto-prefs` 化を検討する。

**`lastCurrentSessionId` の empty-string handling**: 空文字を `null` 扱いにする
(`prefs.remove(KEY)` で永続側からも削除する)。理由: Preferences は string key の
absent と empty string を区別しにくく、復元時に `lastCurrentSessionId = ""` のような
無効値を Settings に流すと FR-PH-54 復元ロジックが「session_id = '' を探す」変な状態
を踏む。Save 側で必ず remove/set を分岐させる。

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

#### 3.6.5 `phone/data/model/` 層

ChatMessage / ImageAttachment / PendingPermission / Settings / ConnectivityState の 5 種類。
すべて `data class` で immutable、HistoryStore (JSON) / DataStore (Preferences) /
runtime state の入れ物として共用する。

##### 3.6.5.1 全モデルに `@Immutable` を付ける (P3-A 4c2 review)

5 ファイル全てが `androidx.compose.runtime.Immutable` annotation 付き。理由:

- Compose の **stability inference** に「中身が変わらない型」だと宣言し、`LazyColumn(key
  = { it.id })` の item recompose skip / `SettingsDialog` 等の引数 stability を効かせる
- data class 自体は reference equality を破る再生成パターンが起きやすい (`copy()` で
  別 instance) ので、annotation 無しだと Compose stability inference が悲観的に倒れて
  recompose 過多になる
- `ConnectivityState.Failed(reason)` のように内部に `String` を持つ sealed class
  にも同 annotation を付ける (`String` は実質 immutable)

##### 3.6.5.2 `ChatMessage` — Phone-local 拡張

`:protocol.ChatMessagePayload` を Phone 側で永続化する型として拡張:

- `id: Long`: HistoryStore の autoinc (§3.6.1 と §2.2.4)
- `chatId: String?`: Hub mint の UUID v4。Phone send 由来 / Hub reply 由来で一貫
- `sessionId: String?`: 確定 session_id (= claude code の `--session-id`)。未確定時 null
- `createdAtMs: Long`: 受信 / 送信タイムスタンプ
- `image: ImageAttachment?`: outgoing のみ。incoming 側は付かない

`ChatMessagePayload` (wire 型) から `image` / `sessionId` / `createdAtMs` を足した拡張で、
Glass 送信時は `chatId` / `image` / `createdAtMs` をドロップして wire payload に縮約。

##### 3.6.5.3 `Settings` — Hub 接続 + OpenAI key + session 復元

| field | 用途 |
|---|---|
| `baseUrl` | Hub の HTTP origin (`http://<host>:<port>`) |
| `token` | X-Token (NFR-20) |
| `openAiApiKey` | 音声入力 (OpenAI Realtime API) |
| `lastCurrentSessionId` | FR-PH-54 / 再起動後の current session 復元 |

`isConfigured: Boolean` は derived property (`baseUrl.isNotBlank() && token.isNotBlank()`)
で、UI から「Hub に繋ぐのに最低限必要な情報が揃っているか」の boolean gate として使う
(SettingsDialog の入力 validation と DataStore 起動時の auto-connect 判定で共有)。

##### 3.6.5.4 `ImageAttachment` — path 永続化方式 (v1.0)

Phone-local 画像は **絶対 path だけ** を data class に持ち、bytes 自体は cache / files
dir に置く方式 (`localPath: String`)。代替案 (base64 を data class に持つ) との
トレードオフ:

| 方式 | メリット | デメリット |
|---|---|---|
| **path 形式 (v1.0 採用)** | 軽い (履歴 JSON が小さく atomic write が速い) | export 可搬性なし (path 切れたら画像消失) |
| base64 形式 | export 時に self-contained | 履歴 JSON が肥大、atomic write の round-trip コスト |

将来 HistoryStore export 機能を入れるとき base64 形式に切り替えを検討するが、v1.0 では
path 形式に倒す。

`sizeBytes` / `longestEdge` は UI 表示 / log 用の hint で、wire には乗せない。

##### 3.6.5.5 `ConnectivityState` — 5 状態と `AuthFailed` の恒久失敗

| 状態 | 意味 | 遷移 |
|---|---|---|
| `Idle` | Settings 未設定 (NotConfigured 相当) | configure → Connecting |
| `Connecting` | 接続試行中 | success → Open / fail → Failed |
| `Open` | SSE 接続中 | server close → Failed |
| `Failed(reason)` | 一時失敗 | exp backoff で再試行中 (ConnectionController §3.2.4) |
| `AuthFailed` | 401 で恒久失敗 | **手動 `reconnect()` を待つ** (auto retry しない) |

`AuthFailed` を `Failed` と分離している理由: 401 は credentials が間違っている = retry
してもまた 401 で battery を浪費する。UI 側で reconnect ボタンを出して user の明示的
リトリガを待つ (NFR-20 の token rotation 運用と整合)。

##### 3.6.5.6 `PendingPermission` — Hub outstanding と 1:1 + `toWirePayload`

Phone-local の保留中 permission。Hub の `OutstandingEntry` (§5.2.3) と 1:1 対応で、
SSE event をそのまま data 化したもの。AD-13 の SSE 再接続時に `permission_snapshot` を
受けて Phone 側 state が再構築される (§3.2.1 の repository ロジック)。

`toWirePayload()` で `:protocol.PendingPermissionPayload` に写像し、Glass 向け CXR
送信に使う (Phone-local 側で持つ `createdAtMs` 等を wire 型側に詰める一方向変換)。

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

#### 3.7.1 `TransientError` (一過性 error の discriminated wrapper)

`ChannelRepository.errors: SharedFlow<TransientError>` の要素型。`:protocol.SharedWireError`
と Phone-local の `PhoneWireError` を 1 つの sealed で束ねる:

```kotlin
sealed class TransientError {
    data class Shared(val error: SharedWireError) : TransientError()
    data class Phone(val error: PhoneWireError) : TransientError()
}
```

UI 側で `mapToPresentation` を経由して `Snackbar / Dialog / Banner` に振り分ける。

**「一過性」の境界線**: ここに流れるのは **snackbar 系の流れて消える error** のみ。
**永続状態** (例: `AuthFailed` で `reconnect` まで recovery しない) は `ChannelRepository.
connectivity: StateFlow<ConnectivityState>` 側で扱う (§3.6.5.5)。1 つの error を SharedFlow
と StateFlow の両方に流すと、UI に同時に banner と snackbar が出てユーザが混乱する。

### 3.8 通知シェード経由 verdict 経路の Receiver

> 下記コードは模式図。実装は `containerOrNull != null` / `ContextCompat.startForegroundService` を使い、Hub credentials missing / 通知 repost を含む。詳細は §3.8.1 / §3.8.2 を参照。

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

#### 3.8.1 in-proc / kill 経路の判定

`PhoneApplication.containerOrNull` を読んで分岐 (§3.3.6.2):

- **in-proc** (`containerOrNull != null`): Repository を `applicationScope.launch` で叩き、通知を直接 cancel。`scope` が null の極端ケースは fresh な `CoroutineScope(SupervisorJob() + Dispatchers.IO)` を fallback として作るが、これは Receiver の lifetime に縛られないので fire-and-forget
- **out-of-proc / kill** (`containerOrNull == null`): `VerdictDispatchService` を `ContextCompat.startForegroundService` で起動 (§3.3.5)

Receiver は短命なので `goAsync` は使わず、in-proc 経路は `PhoneApplication.applicationScope` (= application 生存と同じ scope) に suspend 関数を launch する。

#### 3.8.2 Hub credentials missing 経路の repost (P2-2)

kill 経路で `baseUrl` / `token` extras のどちらかが blank だと verdict が Hub に届かないが、通知は `setAutoCancel(true)` でタップ時点で既に消えており **ユーザは「verdict 送信できなかった」事実に気付けない**。

対処として `notificationId` を再利用して **「再ペアが必要 (Hub の token が見つかりません)」通知を repost** する (`CHANNEL_VERDICT_DISPATCH` を流用):

```kotlin
if (baseUrl.isBlank() || token.isBlank()) {
    log.error("verdict_dispatch_skipped_missing_hub", ...)
    if (notificationId >= 0) {
        val notif = NotificationFactory.verdictDispatch(context, "再ペアが必要です ...")
        notifMgr.notify(notificationId, notif)
    }
    return
}
```

これによりタップ済通知の `AutoCancel` で消えたケースでも repost が見える形で残り、ユーザに「Settings からの再ペアが必要」を促せる。

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

#### 4.3.1 `pendingPermission` の `PendingPermissionPayload` 直接再利用

`PhoneState.pendingPermission` は `:protocol.PendingPermissionPayload` を **そのまま**
持つ (Glass-local の dedicated 型を作らない)。Phone 側 `PendingPermission` (§3.6.5.6)
が `toWirePayload()` で同型を作るので、CXR 受信 → Glass UI 表示まで wire 型のままで
通る。Glass 専用 model に詰め替える分岐を消すことで、`@SerialName` の追跡 (`requestId`
vs `request_id`) を 1 箇所に集約できる。

#### 4.3.2 `GlassNotification` (一過性 banner event)

通知時 banner の一過性 event 用に `GlassNotification(kind, text, sessionId)` を併設。
CXR wire の `NotificationEvent` を Compose 観点で `@Immutable` annotation 付きの
data class として持ち直したもの。Glass UI は SharedFlow で受けて短時間表示するだけ
なので、`@Immutable` で stable inference を効かせる目的のみの薄い wrapper。

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

### 4.6 `GlassGesture` + `GestureBus`

Glass 側で発生したジェスチャを表す内部 sealed (`Tap` / `DoubleTap` / `SwipeForward` / `SwipeBack`、FR-GL-10〜14)。物理リモコン (`MainActivity.dispatchKeyEvent`、§4.5.4) と CXR-L 受信ジェスチャを統一形で UI に流す。

#### 4.6.1 protocol `GestureKind` との分離

wire の `protocol.GestureKind` と **2 つに分けている理由**:
- `GlassGesture` は Glass 内部 (UI dispatch) 用
- `GestureKind` は Phone への wire enum
- `GlassGesture.DoubleTap` は **「会話画面で取消」「session 選択画面で終了」** など local 操作にも使う (= wire 送信せずに完結するケースがある)。直接 protocol 層に結びつけると wire vs local の混在が型から読めなくなる

```kotlin
fun GlassGesture.toWireKind(): GestureKind? = when (this) {
    Tap -> TAP
    DoubleTap -> DOUBLE_TAP
    SwipeForward -> SWIPE_FORWARD
    SwipeBack -> SWIPE_BACK
}
```

`toWireKind` は wire 送信時のみ呼ばれる変換 helper。

#### 4.6.2 `GestureBus` buffer policy (P3-A)

```kotlin
object GestureBus {
    private val _events = MutableSharedFlow<GlassGesture>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    ...
}
```

- **`replay = 0`**: 過去 gesture を後着 subscriber に渡さない (HUD 操作は「今ここの操作」なので過去 replay は無意味)
- **`extraBufferCapacity = 8`**: 連打を吸収
- **`onBufferOverflow = DROP_OLDEST`** (P3-A): default の `SUSPEND` だと `tryEmit` が `false` を返して **新着** gesture が drop される。HUD 操作的には「古い gesture を捨てて新しいのを残す」のが直感的

### 4.7 `SoundEffects`

Glass app の全効果音を 1 箇所に集約 (旧 `NotificationSound` + UI feedback)。

#### 4.7.1 5 種類の Kind と契機

| Kind | 契機 | 検出箇所 |
|---|---|---|
| `INCOMING_REPLY` | Phone からの reply 通知 | `MainActivity` `notifications.collect` |
| `INCOMING_PERMISSION` | Phone からの permission 通知 | 同上、chime 2 連 (220ms 間隔、FR-GL-71) |
| `SEND` | OUTGOING message の最大 id が増加 | `MainActivity` `messagesForSession.collect` (§4.5.2) |
| `RECORD_START` | TAP 押下時点 (state 遷移を待たない) | `ConversationStateHolder.handleIdle` (§4.5.2) |
| `RECORD_STOP` | `transcriptState` が `LISTENING → 非LISTENING` | `MainActivity` `phoneState.collect` (§4.5.2) |

検出は呼び出し側の `collect` で行い、`SoundEffects` は再生だけ担当する責務分離。

#### 4.7.2 context-less `play(Kind)`

`init(context: Context)` で application context を `@Volatile appContext` に保存し、以降は `play(kind: Kind)` で context 引数なしに再生可能:

```kotlin
fun play(kind: Kind) {
    val ctx = appContext ?: return
    play(ctx, kind)
}
```

Compose 非依存層 (`ConversationStateHolder.handleIdle` の RECORD_START 先行発火、§4.5.2) から context を引き回さず使うため。未 `init` の場合は silent no-op (JVM unit test 経路で安全)。

#### 4.7.3 MediaPlayer leak 防止 (P2-B)

`MediaPlayer.create` 後、`setAudioAttributes` 等で **`start()` 前** に例外を投げると、`OnCompletionListener` が登録されないので release されず leak する:

```kotlin
val player = runCatching { MediaPlayer.create(context, resId) }.getOrNull() ?: return
try {
    player.setAudioAttributes(...)
    player.setOnCompletionListener { runCatching { it.release() } }
    player.setOnErrorListener { mp, _, _ -> runCatching { mp.release() }; true }
    player.start()
} catch (t: Throwable) {
    runCatching { player.release() }   // ← create 後の throw を全部 catch して release
    log.warn("sfx_play_failed", t, ...)
}
```

`OnCompletionListener` と `OnErrorListener` の両方で release を呼ぶことで、再生中の error も最終的に release される。複数連発時は MediaPlayer インスタンスを別々に作る (同一 instance を使い回すと再生中の release 競合)。

#### 4.7.4 `NotificationKind → Kind` 変換

```kotlin
fun play(context: Context, kind: NotificationKind) = play(context, when (kind) {
    NotificationKind.REPLY -> Kind.INCOMING_REPLY
    NotificationKind.PERMISSION -> Kind.INCOMING_PERMISSION
})
```

incoming 通知 collect 経路の glue (`MainActivity.notifications.collect`)。FR-GL-71 の chime 2 連は `play(Kind.INCOMING_PERMISSION)` 経由で `Handler.postDelayed(220ms)` する (§4.7.1)。

### 4.8 `ConversationScreen` (会話画面)

FR-GL-30〜33 / FR-GL-50〜62 の HUD 会話画面。`messages` (LazyColumn) + `inputText` + `transcriptText` を mode 別に切り替えて描画する。gesture は `ConversationStateHolder` (§4.4) に委譲し、画面は `holder.state` (mode + cursor) を 1 つだけ観測する。

#### 4.8.1 `rememberUpdatedState` で `onBack` を間接参照

```kotlin
val currentOnBack = rememberUpdatedState(onBack)
val holder = remember {
    ConversationStateHolder(..., onBack = { currentOnBack.value() }, ...)
}
```

`onBack` ラムダは親 `NavHost` の recomposition で別インスタンスに差し替わり得る。`remember` のクロージャに直接キャプチャすると **初回値を握り続ける** ので、`rememberUpdatedState` で間接参照して最新値に追従する。

#### 4.8.2 1 swipe = 1 行スクロール

```kotlin
val lineHeightPx = with(LocalDensity.current) { LINE_HEIGHT_SP.sp.toPx() }
LaunchedEffect(holder) {
    holder.scrollRequest.collect { lines ->
        listState.animateScrollBy(lines * lineHeightPx)
    }
}
```

`LINE_HEIGHT_SP = 21f` は `MESSAGE_FONT_SP = 12f` の **約 1.75 倍** (実機計測。`Arrangement.spacedBy(3.dp)` 込み)。フォント変更時は比率も再計測する。コンテンツ末端の clamp は `animateScrollBy` 側が自動でやる。

#### 4.8.3 CONFIRMING 中の auto-scroll 抑制

```kotlin
val autoScroll = state !is State.Confirming && state !is State.PermissionConfirming
```

確認操作中に最新メッセージ追従で勝手にスクロールされるとユーザが操作対象を見失う UX hazard を避けるため、CONFIRMING / PERMISSION_CONFIRMING のあいだは scroll を止める。

#### 4.8.4 `MessageList` の auto-scroll key (P2-F)

```kotlin
LaunchedEffect(
    messages.size,
    messages.lastOrNull()?.id,
    messages.lastOrNull()?.text?.length,
    autoScroll,
) { ... }
```

- `size` だけでは「旧末尾削除 + 新追加 → 同 size、別 id」を補足できない → `lastOrNull()?.id` を含める
- streaming reply の text 伸長は `lastOrNull()?.text?.length` で補足
- `autoScroll` を key に含めることで、Confirming → 復帰時に最新位置へ追従

#### 4.8.5 強調は INCOMING のみ

`MessageRow` は `role == INCOMING` のときだけ `TextGreen` + `FontWeight.Bold`。OUTGOING / SYSTEM は `TextInactive` で控えめに。**HUD 上で「読みたい内容」(= AI の返信) だけが明るく + 太字** になるので、ログを過去にスクロールしても返信を拾いやすい UX。

#### 4.8.6 `MESSAGE_FONT_SP` / `LINE_HEIGHT_SP` の集約 (P3-B)

`private const val MESSAGE_FONT_SP = 12f` / `LINE_HEIGHT_SP = 21f` を file-private const にして、`MessageRow` / `InputLine` / `ConfirmBar` / `PermissionConfirmBar` の 4 か所で参照する。フォントサイズ変更は 1 か所で済む。

#### 4.8.7 `ScreenAwakeManager` 連動

```kotlin
DisposableEffect(lifecycleOwner) {
    val handle = ScreenAwakeManager.acquireWhileStarted(context, lifecycleOwner.lifecycle)
    onDispose { handle.close() }
}
```

会話画面が STARTED の間 `SCREEN_BRIGHT_WAKE_LOCK` を握る (詳細は `ScreenAwakeManager`)。`DisposableEffect` で離脱時に release を保証。

#### 4.8.8 空 input の `Confirming` 防御 (P3-E)

通常は phone 側で送信前に `clearInput → CONFIRMING 遷移` は出ない契約だが、`ConfirmBar` で **`input.isNotEmpty()` ガード**して送信プレビュー行をスキップする念のための防御。HintLine + 送信/取消の選択肢のみ表示。

### 4.9 `SessionSelectScreen` (session 選択画面)

FR-GL-20〜22 の session 一覧。前後 (Swipe) でカーソル移動、Tap で決定 → `sendSelectSession` + 会話画面に遷移、DoubleTap で app 終了。カーソル index は **glass-local state**。

#### 4.9.1 `index` を `mutableIntStateOf` で **単一 State** として保持

```kotlin
var index by remember { mutableIntStateOf(0) }
```

`remember(sessions.size)` でキー指定すると sessions が空 → 非空に変わったタイミングで `MutableIntState` インスタンスが差し替わり、`LaunchedEffect` が古い State を持ち続けて反映されなくなる。**単一 State として保持**し、別 `LaunchedEffect` で current / size に合わせて補正する。

#### 4.9.2 初回マウント時のみ `current` に追従 (P2-A)

```kotlin
var didInitialAlign by remember { mutableStateOf(false) }
LaunchedEffect(sessions, current) {
    if (!didInitialAlign && sessions.isNotEmpty()) {
        val byCurrent = sessions.indexOfFirst { it.id == current }
        if (byCurrent >= 0) index = byCurrent
        didInitialAlign = true
    }
    // size 変化 clamp は常に走らせる
    if (sessions.isEmpty()) index = 0
    else if (index >= sessions.size) index = sessions.size - 1
}
```

ユーザが session B にカーソルを動かしている最中に phone 側 `currentSessionId` が C に変わると (reply auto-switch、§3.2.1.3.1)、カーソルが勝手に C へジャンプする UI hazard があった。**FR-GL-20〜22 はカーソル位置の追従を要求していない** ので、初回マウント時のみ align してそれ以降はユーザ意志を尊重する。size 変化に対する clamp はユーザ意志を上書きしないので常に走らせる。

### 4.10 `ScreenAwakeManager`

グラスの display 電源管理の単一窓口。

#### 4.10.1 `FLAG_KEEP_SCREEN_ON` を使わない理由

Rokid YodaOS では `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` が **CXR-L 経由の reply 受信などをきっかけに事実上無効化される** ことを実機で確認。framework の暗黙 user-activity 経路に頼ると、HUD で reply を受け取った瞬間に画面が暗転する hazard が出る。

このため画面 ON を維持したい場面では **`SCREEN_BRIGHT_WAKE_LOCK` を自前で acquire** する方式に倒す (deprecated API だが Glass 環境では必須)。

#### 4.10.2 2 つの責務

| API | 用途 | 仕様 |
|---|---|---|
| `wakeOnNotification(ctx)` | 画面 OFF からの一発 wake-up | `SCREEN_BRIGHT_WAKE_LOCK \| ACQUIRE_CAUSES_WAKEUP \| ON_AFTER_RELEASE`、`acquire(3s)` |
| `acquireWhileStarted(ctx, lifecycle)` | `Lifecycle.STARTED` の間ずっと画面 ON | `SCREEN_BRIGHT_WAKE_LOCK \| ON_AFTER_RELEASE`、`acquire(10 min timeout)` |

`releaseNotificationWake()` は Activity 終了時の保険解放 (`MainActivity.onDestroy`)。

#### 4.10.3 通知 wake-up と長期 WakeLock の干渉防止

```kotlin
if (pm.isInteractive) {
    log.debug("wake_skip_interactive")
    return
}
```

会話画面が `acquireWhileStarted` で長期 WakeLock を握っている間に通知 wake-up が走ると、3 秒 timeout で release されたタイミングで画面が落ちる hazard がある。`PowerManager.isInteractive == true` の間は **wake-up を skip** することで、長期側に主導権を残す。

#### 4.10.4 `KEEP_ON_TIMEOUT_MS = 10 min` の根拠 (P2-A)

`ON_STOP` を経由せずに process kill された場合、`PowerManager` 側に「無期限 acquire のまま hold」状態が残り得る。10 分 timeout を付け、Activity が再 ON_START したときに **再 acquire される設計** に倒す:

- 10 分以内に Activity が再開すれば表示は維持
- 再開しないなら kill 経路の WakeLock leak を OS 側で自動解除
- `ON_START` のたびに `wl.takeIf { !it.isHeld }.acquire(KEEP_ON_TIMEOUT_MS)` で timeout を refresh

#### 4.10.5 `KeepOnHandle` (AutoCloseable)

`acquireWhileStarted` の戻り値で `LifecycleEventObserver` と `WakeLock` の両方を所持。`close()` で:
1. `lifecycle.removeObserver(observer)` (observer leak 防止)
2. `wl.takeIf { it.isHeld }.release()` (WakeLock 解放)

Compose の `DisposableEffect.onDispose { handle.close() }` で必ず呼ぶ契約 (`ConversationScreen` §4.8.7)。

### 4.11 Glass UI 補助モジュール

§4.7〜4.10 で扱った主要 composable / manager の補助ピース。それぞれ単機能で短いが、
HUD 固有の制約 (単色緑 / 細い border / NavHost / DoubleTap = 終了) から派生した非自明な
WHY を 1 箇所に集約する。

#### 4.11.1 `theme/Color.kt` (HUD 緑パレット)

Rokid Glass は **単色緑モノクロ HUD**。輝度のみが効くが、ソース側を全部緑系で揃えると
HUD 発色 と PC mirror (scrcpy) の見え方のトーンが一致して開発中の視認性が上がる。

| 役割 | HEX | 用途 |
|---|---|---|
| `TextGreen` | `0xFF4DFF6F` | active テキスト (明るい緑) |
| `TextGreenDim` | `0xFF7FCC8A` | hint / inactive (やや暗い緑) |
| `TextInactive` | `0xFF5DB070` | 非選択行 / 非 latest メッセージ |
| `TextChevronDim` | `0xFF4A8C58` | focus 矢印 ▶ の非 focused 側 |
| `GlassBackground` | `Color.Black` | 背景 |

**G チャンネル輝度の選択根拠**: `TextInactive` の `G=0xB0` (176) は max 255 から十分
落ちる読みやすい輝度。`0x80` (128) だと HUD では暗すぎて読めなくなる (実機計測)。

#### 4.11.2 `SessionNavigator` (signal-only nav bus)

通知 / CXR 経由の自動遷移を Compose の NavController に橋渡しする小さな bus
(`MutableSharedFlow<Unit>(extraBufferCapacity = 4)`):

- `MainActivity (lifecycleScope)` から `requestConversation()` を呼ぶ
- `NavHost` を持つ composable が `LaunchedEffect` で `requests.collect { ... }` し
  `nav.navigate(GlassRoutes.CONVERSATION)` を実行

**`Unit` で十分な理由**: 「conversation 画面を出して」という signal のみで、どの
session に行くかは **Phone 側 currentSessionId が真実**。caller は別途
`GlassBridge.sendSelectSession(id)` を叩く前提なので、payload を bus に乗せない設計。

#### 4.11.3 `GlassNavHost` (2 route + pop semantics)

```
SESSION_SELECT (startDestination)
    └── (tap で選択) → CONVERSATION
                          └── (back) → popBackStack(SESSION_SELECT)
```

CONVERSATION からの戻りは `nav.popBackStack(SESSION_SELECT, inclusive = false)`。
SESSION_SELECT への遷移時は `launchSingleTop = true` + `popUpTo(SESSION_SELECT) {
inclusive = false }` で stack 重複を防ぐ。

**FR-GL-52 (セッション跨ぎで確認状態を保持) は未対応 — P2-B 5b review**: 上の
`popBackStack(inclusive = false)` は CONVERSATION の back stack entry を **dispose**
する → 再 nav で `ConversationStateHolder` の `remember` 状態 (`sendChoice` /
`permissionChoice` のホバー位置) が破棄される。FR-GL-52 は Should 要件で 5c 以降の
課題として保留。実装するときは Holder の state を Activity 層 (ViewModel 等) に持ち
上げる必要がある。

#### 4.11.4 `PhoneConnectionGate` (CXR-L 接続 gate + 早期 DoubleTap 受け)

`GlassBridge.sessionOpen: StateFlow<Boolean>` を観測し、`true` のときだけ content
(NavHost) を mount する gate。Phase 3 §4.4 + FR-GL-02 / FR-GL-05。

**未接続中の DoubleTap = 終了 を専用 LaunchedEffect で受ける**: 未接続中は
SessionSelect / Conversation がマウントされないため、DoubleTap を誰も拾わない。Gate
側で `LaunchedEffect(Unit) { GestureBus.events.first { it == DoubleTap }; onExit() }`
を購読する。接続成立で if 分岐が剥がれると LaunchedEffect は終了する (`DisposableEffect`
不要)。

**P1-B 5b review (`first` vs `collect`)**: 当初 `collect` 内で待っていたが、`onExit()` →
`finish()` が非同期に完了するまで collector がループに残り、**連打 DoubleTap で
onExit が 2 回呼ばれる race** があった。`first { ... }` で 1 件で抜けて以降の
DoubleTap は bus に積まれるだけにする (= 多重 finish 防止)。

未接続中の status text は `GlassBridge.status` に応じて `phone 待機中… (CONNECTED)` /
`phone 接続中… (CONNECTING)` / `phone と接続待ち (DISCONNECTED)` を出し分ける。

#### 4.11.5 `ChatFrame` (border-only HUD container)

会話領域を囲む細いグリーンの枠 (`RoundedCornerShape(10.dp)` + `border(1.dp, TextGreen,
shape)`)。**塗りつぶしを使わない理由**: Rokid HUD は緑モノクロで、塗りつぶしは目に
眩しい (1 dp 単位の輝度差を読むデバイス特性)。領域分けは細い border 1 つで表現し、
中身は黒背景のまま読ませる方針。

#### 4.11.6 `theme/Theme.kt` (Material3 darkColorScheme 固定)

`GlassTheme` は Material3 の `dynamicColor` (Android 12+ wallpaper-based palette) を
**使わない**: HUD は単色緑なので OS 側のテーマ色が混ざると逆に読みにくい。
`darkColorScheme(primary=TextGreen, onPrimary=GlassBackground, ...)` で **全 slot を
緑系で固定**する。

- `primary` / `onPrimary*` / `secondary` / `surface` / `background` を `TextGreen` /
  `GlassBackground` (Color.Black) の 2 色で組む
- `MaterialTheme` で wrap してから `Typography` を渡す (`Type.kt` 側の HUD 用 sp 定義)

### 4.12 構造化ログ

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

Phone ↔ Hub の HTTP/SSE server。`0.0.0.0` で listen し、LAN / Tailscale どちらの
NIC からも受ける (§5.5.2 と対)。エンドポイント:

| method | path | 用途 |
|---|---|---|
| POST | `/send` | text + 画像 を Bridge 経由で Claude へ |
| POST | `/permission` | verdict を Bridge 経由で Claude へ |
| GET  | `/events` | SSE。接続成立直後に snapshot を順送 (§5.2.4) |

##### 5.2.2.1 X-Token 認証 (NFR-20) と timing-safe 比較

config の `token` が non-null のときは全 endpoint で `X-Token` header を要求し、
`constantTimeEquals` (`node:crypto.timingSafeEqual`) で比較する。文字列の `===` は
最初に差異が出た文字で短絡するため、ms オーダーの timing leak で token を 1 文字ずつ
brute force される攻撃 (NFR-20) を防ぐ目的で固定時間比較が必須。`token === null` の
場合は認証無効 (開発時のみ、`.env` で token 未指定の運用)。

##### 5.2.2.2 BODY_LIMIT_BYTES = 16 MB + 上限超過後の drain 戦略

`/send` は `image_base64` を含むので body 上限を 16 MB と大きめに取る。上限超過を
検知したら以降の chunks を accumulate せずに **req は drain し続ける** (= ストリーム
は最後まで読む)。理由: `req.destroy()` で即切断すると client が ECONNRESET でレスポンス
を受け取れない (= `image_too_large` の error_code が Phone に届かない)。drain しきった
後で 400 を JSON body 付きで返す方が、Phone 側の error 表示まで含めた UX が安定する。

##### 5.2.2.3 `mapReadError` の endpoint 分岐

`too_large` を返す経路は `/send` (画像込み) と `/permission` で意味が違う:

- `/send` の `too_large` → `IMAGE_TOO_LARGE` (Phone は image_too_large として UI 通知)
- `/permission` の `too_large` → `INVALID_PAYLOAD` (本文が軽い endpoint で上限超過は payload 壊れ)

`invalid_json` / `empty` はどちらも `INVALID_PAYLOAD`。

##### 5.2.2.4 `resolveSessionId`: 単一 session で自動採用

`/send` の body に `session_id` が無いときの解決:

- active session 数 = 1 → その唯一の session を採用 (Phone の UX 単純化)
- active session 数 ≠ 1 (0 or 複数) → null を返して `SESSION_NOT_ACTIVE` で 4xx

`session_id` を明示指定した場合は `SessionRegistry.get()` で存在検証してから採用。
存在しない session_id は null → 4xx。

##### 5.2.2.5 `dispatchVerdict` 失敗時の Phone abort broadcast (P1-1)

verdict の Bridge への転送が失敗した場合、Phone 側は既に pending entry を消して
verdict を送ったつもりになっている (= Phone の local state は「verdict 送信済」)。
Hub 側でこの不整合を残すと「verdict 送ったのに UI に反映されない」体感バグになる。
対策として失敗パスでは:

1. `permission_abort` を Phone に broadcast (`reason: "no_session"` / `"dispatch_failed:<reason>"`)
2. `/permission` 自体は 400 で `SESSION_NOT_ACTIVE` を返す

を両方やる。broadcast で Phone の visual state を同期し、4xx で UI に「verdict は届かなかった」を明示する二重化。

`dispatchVerdict` の `boolean` 戻り値はこの **response 二重送防止** チャンネル: `false` を
返すときは「失敗 path で既に `res` へ 4xx を書いた」ことを意味し、caller
(`handlePermission`) は追加 response を送らない (success の場合のみ caller が 200 と
ログを emit する)。

##### 5.2.2.6 `entry.sessionId === null` 経路 = race 徴候

Outstanding entry の `sessionId` は通常 non-null (`BridgePermissionMessage` で必ず
session_id が来るため)。null が来る経路は、protocol の future extension や bug の
徴候 (= race condition の可能性)。`verdict_no_session` warn ログを吐いてから上記
P1-1 ルートで abort broadcast + 4xx を返す。

##### 5.2.2.7 `/events` snapshot 順送

SSE 接続成立直後に `session_snapshot` → `permission_snapshot` → 個別 `permission`
(`createdAtMs` 昇順) の順で snapshot を送出する (詳細フォーマットは §5.2.4)。
keepAlive timer (`sseKeepAliveMs` 既定 15s) で proxy の idle timeout を回避。

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

##### 5.2.3.1 127.0.0.1 専用 listen (NFR-22)

`BridgeServer` は **loopback (`127.0.0.1`) でしか listen しない**。Bridge は同 host
の Claude Code から spawn された子プロセスで、外部 LAN からの TCP は届く必要が無い。
NFR-22 (Bridge 通信を外部 NIC に晒さない) を listen host で物理的に保証する設計。

##### 5.2.3.2 `BridgeDispatchResult` discriminated union

`sendToSession` の戻り値は `{ ok: true } | { ok: false; reason: "not_registered" |
"write_failed" }`。呼び元 (`HttpServer.handleSend` / `dispatchVerdict`) が失敗理由
ごとに処置を分けられるよう reason を含める:

- `not_registered`: 該当 `session_id` の socket が `SessionRegistry` に無い
  (Bridge 未起動 or 死亡)
- `write_failed`: socket は存在したが write が失敗 (destroyed / 同期 throw)

呼び元はどちらも `SESSION_NOT_ACTIVE` を Phone に返すが、ログには `reason` を残して
post-mortem を可能にする。

##### 5.2.3.3 `writeNdjson` の backpressure は `ok:true` 維持

`socket.write` が false を返した (= kernel send buffer 満杯) ケースは `write_backpressure`
warn ログを吐くが **戻り値は `ok: true`**。kernel buffer には乗っているので drop では
なく、Node の `drain` event で自然に流れる。`ok: false` に倒すと Phone に
`SESSION_NOT_ACTIVE` が誤通知される。一方 `destroyed` / `writableEnded` / 同期 throw は
`write_failed` で `ok: false`。

##### 5.2.3.4 NDJSON line buffer の split

`onData` で `state.buffer` に utf-8 chunk を蓄積し、`\n` を見つけるたびに行を取り出して
`handleLine` に流す。chunk 境界が JSON object の途中に来る (kernel buffer や MTU
事情) ケースで JSON.parse が壊れないよう、行単位の framing は Hub 側で必ず buffer
する必要がある (TCP は stream で message 境界を保たない)。

##### 5.2.3.5 register-then-validate 三段ガード

ハンドラ入口で以下 3 つを連続して check し、すべて通った時だけ実処理に進む:

| ガード | 条件 | 失敗時 |
|---|---|---|
| `state.sessionId !== null` 既存 | `register` 二重発火 | `double_register` warn + 無視 |
| `state.sessionId !== null` 必須 (`rejectIfNotRegistered`) | `register` 前に reply/permission 等 | `msg_before_register` warn + 無視 |
| `state.sessionId === msg.session_id` (`rejectIfSessionMismatch`) | 1 socket = 1 session の trust boundary | `session_id_mismatch` warn + 無視 |

trust boundary 違反を 4xx ではなく **silent reject** にしている理由: Bridge は同 host
の子プロセスで、本来 mismatch は起こり得ない (= bug or 攻撃)。後者なら過剰応答せず
ログだけ吐く方が攻撃面が小さい。

##### 5.2.3.6 知らない `request_id` の `permission_abort` は broadcast しない (P1-2)

Bridge から `permission_abort` が来たが `OutstandingPermissions.remove` が
`undefined` を返した (= Hub の outstanding にいない) ケースは Phone に broadcast
しない。Phone は既に local pending から消去している (verdict 送信済 or 自発 dismiss)
ので、broadcast すると **存在しない pending を消そうとする noise** が Phone 通知
shade に出る。debug ログだけ吐いて drop。

##### 5.2.3.7 Bridge 切断時の FR-HU-13 一括 abort

socket `close` event で `onClose` が発火し、当該 `bridgeSessionId` 由来の outstanding
を `OutstandingPermissions.onBridgeDisconnected` で一斉に Phone へ abort broadcast +
内部からも削除する。これにより Bridge が死んだあとも Phone に幽霊 pending が残らない
(FR-HU-13)。`session_inactive` も同時に broadcast し、Phone 側 session list を更新。

##### 5.2.3.8 exhaustive `never` で wire 型 drift 検出

`handleLine` の `switch (msg.type)` 末尾で `const _exhaust: never = msg;` を入れ、
`BridgeToHubMessage` union に新 variant が増えたらコンパイル時に拒否させる
(HubClient §6.2.2.7 と同形のガード)。

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

### 5.5 ペアリング QR (`hub/src/pair.ts`)

`npm run pair lan|ts` (= dispatcher `claude-mobile-hud pair`) で実行する CLI。host
の IPv4 と Hub token から payload を作って `qrcode-terminal` で QR を描画する。
Phone 側 `SettingsDialog` の「QR スキャン」が読み取って自動入力する経路 (§3.2.5
Pairing と対をなす)。

#### 5.5.1 named export を vitest で固定する (P2-A/B)

`buildPayload` / `pickAddress` / `score` を **named export** にし、`hub/test/pair.test.ts`
からゴールデン検証する。理由は wire shape の drift を CI で機械検出することで、
Phone `Pairing.QrPayload` (`{v:1, baseUrl, token}`) と Hub `buildPayload` が片方だけ
変わった瞬間にテストが落ちる構造を作る (§3.2.6.2 と対称)。`main()` 側は I/O だけ
に絞り、純粋な変換は全て export 済の関数に集約する。

#### 5.5.2 NIC scoring と `HUB_PAIR_HOST` 脱出口 (P2-F)

`score(name, addr, mode)` は host の複数 NIC から「Phone が一番繋がりやすい」候補
を選ぶための重み付け:

| NIC pattern | score | 補足 |
|---|---|---|
| `tailscale*` / `100.x.x.x` | 100 | mode=ts のときのみ accept |
| `wl*` / `wlan*` (wireless) | 50 | mode=lan |
| `en*` / `eth*` (wired) | 30 | mode=lan |
| `virbr*` / `docker*` | -20 | 仮想 bridge → 除外寄り |
| その他 | 0 | tie-break で残る |

mode が lan のときに tailscale を score `null` にし (= 候補から除外)、mode が ts の
ときに非 tailscale を `null` にする (= 排他 filter)。

**`en*` prefix の wide-net**: Linux の `enp` / `eno` / `ens` / `enx` (USB tether) も
全部拾うため、複数 IPv4 を持つ host では `en*` 同点の中から先頭が選ばれて Phone から
到達できない address が当たることがある。実害時に手で外せる脱出口として
`HUB_PAIR_HOST` env を `pickAddress` の最優先 short-circuit にしている (= NIC 自動
判定をすべて bypass)。env が空文字のときは通常経路に落ちる。

#### 5.5.3 `buildPayload` の wire 契約

payload は `JSON.stringify({ v:1, baseUrl, token })`。**key 名 / version / 順序** が
Phone 側 `Pairing.QrPayload` (`@SerialName` 明示、§3.2.6.2) と文字レベルで一致する
必要がある。`pair.test.ts` の `'{"v":1,"baseUrl":"<url>","token":"<tok>"}'` 完全一致
スナップショットでこれを 1 箇所にロック (key 順は V8 の object literal property order
で保証される)。

#### 5.5.4 token 表示の mask vs QR 画像の漏れ (P3-C)

terminal 表示の `token: xxxx...yyyy (N chars)` は録画/スクショ対策の **mask**。
ただし **QR 画像自体には full token が乗っている**ため、画面をカメラ撮影 / 録画
されると token は漏れる (録画から QR を抽出可能)。mask は「QR を撮らない録画」での
緩和でしかなく、defense-in-depth ではない点を明示しておく。token rotation
(`claude-mobile-hud rotate-token`) はこの前提で運用する。

#### 5.5.5 `isDirectExec` で CLI と vitest import を両立

`import.meta.url === \`file://${process.argv[1]}\`` で「直接 `tsx src/pair.ts` で
起動された場合のみ `main()` を呼ぶ」guard を入れる。vitest から `import { score }
from "../src/pair.js"` で取り込んだときに `process.argv[2]` 不在で `main()` が
USAGE を吐いて `process.exit(1)` するのを避ける目的。

### 5.6 Hub bootstrap / shutdown wiring (`hub/src/index.ts`)

Hub daemon の entry。Phone 向け HTTP/SSE と Bridge 向け NDJSON TCP を 1 プロセスで
立ち上げ、両 server の循環参照を constructor 順で解く。

#### 5.6.1 起動順序

```
loadConfig() → StructuredLog(root)
            → SessionRegistry / ChatRegistry / OutstandingPermissions
            → HttpServer (deps: registries + dispatchToBridge クロージャ)
            → BridgeServer (deps: registries + phoneBroadcast クロージャ)
            → Promise.allSettled([bridge.listen, http.listen]) で並列 listen
            → SIGTERM/SIGINT handler 登録
```

`HttpServer` / `BridgeServer` の循環依存 (HTTP → Bridge へ verdict 転送 / Bridge →
HTTP へ SSE broadcast) はクロージャで結ぶ:

- `dispatchToBridge: (sid, msg) => bridge.sendToSession(sid, msg)` — `HttpServer`
  constructor で `bridge` を参照するが、構築順は HTTP が先なので **クロージャ呼び出し
  時 (= request 受信時)** に `bridge` が evaluate される (`let bridge` の hoisting に
  依存。TS の class 宣言は値レベルで hoist しないため、`const bridge = new
  BridgeServer({...})` が `HttpServer` constructor 後に来ても dispatchToBridge の中
  までは forward reference にならない)
- `phoneBroadcast: (event) => http.broadcast(event)` — 同様、Bridge は HTTP より後に
  構築されるので Bridge constructor から `http` を参照しても問題ない

#### 5.6.2 並列 `listen` と Promise.allSettled で片肺 cleanup

`bridge.listen` と `http.listen` を `Promise.allSettled` で並列実行する。理由: どちらか
が port 衝突で失敗した場合、もう片方は既に listen 状態になっている可能性があり、その
まま rethrow すると **socket が裏で生きたまま supervisor 再起動を待つ** ことになる
(port が 2 つとも開いたまま残る最悪ケース)。allSettled で両方の結果を見て、fulfilled
の方を `close()` してから failure を rethrow する。

#### 5.6.3 `shutdown` と signal handler

`SIGTERM` / `SIGINT` の 2 入口で同じ `shutdown(signal)` を呼び、`Promise.allSettled
([bridge.close(), http.close()])` で並列に閉じてから `process.exit(0)`。Bridge 側の
§6.4.3 と違って **shuttingDown flag は持たない**: Hub の close は再入で問題が起きる
ような状態 (file system / inbox) を持たないため、idempotency は `server.close()` 側に
任せる。

#### 5.6.4 fatal error 経路

`main().catch()` で `process.stderr.write` + `process.exit(1)`。Logger 不在の bootstrap
失敗 (config 不正 / port 衝突発生前のセットアップ失敗) のための最後の砦。Bridge §6.4.5
と同じ paranoia。

### 5.7 Hub `Config` (`hub/src/config/Config.ts`)

env のみから読む小さな pure module。CLI / `.env` ファイルは dispatcher
(`claude-mobile-hud hub` → `tsx --env-file-if-exists=.env`) が前段で env に展開する
ので、Hub プロセスからは `process.env` 一本で済む。

#### 5.7.1 env と default

| env | default | 用途 |
|---|---|---|
| `HUB_HTTP_PORT` | `8788` | Phone 向け HTTP/SSE port |
| `HUB_BRIDGE_PORT` | `8787` | Bridge 向け NDJSON port (loopback) |
| `HUB_TOKEN` | (空) | X-Token (空文字 / 未設定 は **auth off**) |
| `HUB_SSE_KEEPALIVE_MS` | `15000` | SSE keep-alive 間隔 |

#### 5.7.2 `HUB_TOKEN` 空文字 = auth off (dev only)

`env.HUB_TOKEN && env.HUB_TOKEN.length > 0` 以外は `null` を返す。`null` を受けた
`HttpServer.checkAuth` は **全 request を accept** する (§5.2.2.1 の `expected === null`
分岐)。`hub/.env.example` には `HUB_TOKEN=...` を必ず書く運用にし、空 token は dev
ローカルのみの抜け道として残す (本番は `claude-mobile-hud rotate-token` で auto 生成)。

#### 5.7.3 `parseIntOrDefault` の正整数ガード

port や keep-alive ms は **正の整数** だけを受け、`0` / 負 / `NaN` / 文字列はすべて
default に fallback する (`Number.isFinite(n) && n > 0`)。誤って `HUB_HTTP_PORT=abc`
を渡しても node が parse 段階で死なず、ログに「default が効いた」と分かるように
fail-soft に倒す (Bridge `SessionDetector` の fail-fast とは設計方針が違う:
session_id は fallback 不可、port は明示 default あり)。

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

Claude Code 側との stdio MCP server。Phone→Claude の送り (Hub → Bridge → Claude)
と Claude→Phone の返し (Claude → Bridge → Hub) を仲介し、permission の双方向 forward
を担う:

- tool: `reply(chat_id, text)` — Claude が呼ぶ → Hub に reply 転送
- 受信 notification: `notifications/claude/channel/permission_request` (Claude → Bridge)
  → Hub に permission 転送
- 受信 notification: `notifications/claude/channel/permission_abort` (Claude → Bridge)
  → Hub に permission_abort 転送
- 送出 notification: `notifications/claude/channel` (Bridge → Claude) — Hub の send を
  staging 後 (画像があれば `meta.image_path` 付与)
- 送出 notification: `notifications/claude/channel/permission` (Bridge → Claude) —
  Hub からの permission_verdict

##### 6.2.1.1 stdout は MCP プロトコル専有

Claude Code との stdio MCP は stdout を NDJSON で占有する。**`console.log` は絶対に
使わない** (1 byte でも混ざると MCP framing が壊れて Claude が disconnect する)。
`StructuredLog` は stderr 一本に固定。これは Bridge コード全体 (HubClient / SessionDetector
/ ImageStaging も含む) に効く invariant で、ログ出力経路はすべて Logger 経由のみ。

##### 6.2.1.2 capabilities と Claude への INSTRUCTIONS

`capabilities.experimental` に `claude/channel` と `claude/channel/permission` の 2 つを
declare する。これは MCP 公式仕様には**無い** custom capability で、Claude Code 側が
この 2 つを discover した場合に対応する `notifications/claude/channel{,/permission,
/permission_request,/permission_abort}` method を listen / emit してくれる前提
(`--dangerously-load-development-channels server:channel` flag で gate される、§9.3.2)。

server 初期化時に `instructions` を渡し、Claude に「`reply` tool を使え / `chat_id` は
inbound meta を verbatim で / `notifications/claude/channel*` を自分から emit するな /
permission notification は過去の tool call の verdict」を明示する。`instructions` は
Claude が conversation context として常に参照するので、INSTRUCTIONS の表現が channel
プロトコルの user-facing contract になる。

##### 6.2.1.3 SERVER_VERSION は `package.json` 経由 (P3-1)

server name/version の version は `import.meta.url` 基準で `../package.json` を sync
read し、JSON parse して `version` を取り出す。文字列リテラルで書くと bridge 側のパッケージ
版数と二重管理になり、リリースのたびに乖離する。読めなかった場合は `"0.0.0"` に fallback
(Claude Code 側で version 検証は厳格でないため致命的でない)。

##### 6.2.1.4 `writeQueue` で `deliverSend` を全 chat 直列化

`writeQueue: Promise<void>` を chain する形で `deliverSend` を **全 chat 横断で直列化**
する。理由: `image_base64` の staging が `async` (file write) なため、ある send の
image 保存中に次の send の notification が先に飛ぶと Claude 側で順序が逆転する
(meta.image_path 待ちのテキストが、後続の image なし send より遅れて届く)。`then` で
連鎖させることで、各 send 内の `await images.save()` 〜 `await notify()` が直列化される。

- スコープ: `notifications/claude/channel` (= deliverSend) のみ
- 通さない: `deliverPermissionVerdict` (= `notifications/claude/channel/permission`)。
  permission は channel と独立した notification 経路で、互いの順序を縛る必要がない
- メモリ安全性: `this.writeQueue = this.writeQueue.then(...)` で chain を更新するが、
  V8 は resolved Promise の prior 参照を保持しないので、N 回 chain しても深さ N の
  参照グラフは残らず GC される (= 長時間稼動でメモリは伸びない)

##### 6.2.1.5 `onclose` は `connect()` の前に張る (P1-2)

`server.onclose` の代入は `await server.connect(transport)` より**前**にやる。`connect()`
の `await` 中に transport が `close` を発火しても取りこぼさないため。順序を逆にすると
stdio が即時 close するケース (parent kill / pipe closed) で `onClose` callback が
呼ばれず Bridge が hang する。

##### 6.2.1.6 `reply` 失敗時の `isError` 返却 (P2-8)

Hub と切断中に `reply` tool が呼ばれた場合、`HubClient.sendReply` は `false` を返す。
このとき MCP の `CallToolResult` を `{ isError: true, content: [{ type: "text", text:
"hub disconnected; reply dropped" }] }` で返す。`isError: false` の通常返却にすると
Claude 側で「届いた」と誤認して再送しないため。

##### 6.2.1.7 image_path / 空 content の fallback ルール

`deliverSend` の image 取り扱いで 2 種類の fallback が並走する:

1. **staging 成功 + 空 text → `content = "(image)"`**: empty text + image のケースで
   Claude が「空 content notification」を読み飛ばすのを防ぐため、`(image)` の placeholder
   を埋める。Claude 側で `meta.image_path` だけ読んでも使えるよう、最低限の text を
   保証する。
2. **staging 失敗 → text-only で継続**: `images.save(base64, mime)` が throw した場合
   (未対応 MIME / disk full 等) は warn ログを吐いて image 抜きで notification を継続
   する。text + image の組み合わせを「image なしでも text は届ける」優先順位に倒して
   いる (会話全体が止まるよりは degraded delivery)。

##### 6.2.1.8 transport seam

`McpServerOptions.transport` で `Transport` を差し込める。テスト時 (`bridge/test/mcp-server.
test.ts`) に `InMemoryTransport` を差し、stdio を介さず in-process で notification flow
を検証する目的。default は `StdioServerTransport` (本番経路)。

#### 6.2.2 `HubClient` (register / ack 待ち、§1.3 D-中 対応)

Hub の `BridgeServer` への TCP NDJSON クライアント (port 8787 / loopback)。接続後の
最初の write は必ず `register`、Hub の `ack_register` 返信までは他の send / permission
/ permission_abort を `outgoingQueue` に積み、ack 到着で順送 flush する。

##### 6.2.2.1 NDJSON over loopback TCP

socket は `node:net` で 127.0.0.1:8787 (host 既定) に connect。受信は `readline.
createInterface({ input: socket })` で行単位、各行を `JSON.parse` → `HubToBridgeMessage`
として処理。送信は `JSON.stringify(msg) + "\n"` の一行を `socket.write`。

##### 6.2.2.2 register-then-queue (D-中 race 対策)

`connect()` の Promise は `register` の **write 完了** で resolve する (`ack_register`
は待たない)。Bridge は `connect().then(...)` の中で即 `reply` などを呼ぶ可能性があり、
ack 待ち blocking は Bridge の起動 latency に直接乗るため避ける。代わりに ack 到着前の
write は `outgoingQueue` に積み、`handleLine` で `ack_register` を受けた瞬間に
`flushQueue` で順送する。

##### 6.2.2.3 socket error handler を先張りする (P3-5)

`createConnection` の戻り値 socket に対して、`once("connect")` / `once("error")` で
connect 結果を待つ前に、**先に** `sock.on("error", ...)` と `sock.on("close", ...)` の
**永続** handler を張る。`once("error")` を await 終了で外したあとに来た error が
unhandled になり、Node が `Error: read ECONNRESET` を throw して process crash させる
事故を避ける目的。

##### 6.2.2.4 register write 失敗 → `connect()` 自体を fail (P1-3)

`writeRegisterNow` が `false` を返した (socket destroyed / 同期 throw) 場合、
`connect()` は `close()` してから `Error("register write failed immediately after
connect")` を throw する。`writeRegisterNow` が true でも backpressure が起きていれば
warn ログだけ吐いて成功扱い (kernel buffer に乗ったかは確認できているため)。`ack_register`
が Hub から戻るかは別経路の watchdog 余地として残す (Phase 5 拡張)。

##### 6.2.2.5 `sessionId` は HubClient が抱える (P2-6)

Bridge は **1 プロセス = 1 session** なので、`opts.sessionId` を constructor で受けて
HubClient 内部に保持する。`sendReply(chatId, text)` のように呼び出し側は session_id を
再渡ししない (sessionId を毎回引数で受けると、誤って違う session_id を混ぜる事故が
1 行のミスで起きる、§3.2.5.4 と同じ「ハンドル抱え持ち」パターン)。

##### 6.2.2.6 `onClose` の `remote` / `local` 弁別

socket `close` event のハンドラ `onClose()` は、`this.closed` flag を見て disconnect
原因を `"local"` (= `close()` が呼ばれた = Bridge 側で `shutdown` を発火した) /
`"remote"` (= Hub 側 TCP 切断) に分類して callback に渡す。Bridge `index.ts` 側で
`remote` を受けたら `shutdown("hub_remote_close")` で自殺する (§5.3 と対)。

##### 6.2.2.7 exhaustive `never` で wire 型 drift をコンパイル時検出

`handleLine` の `switch (msg.type)` 末尾で `const _exhaust: never = msg;` を入れて、
`HubToBridgeMessage` union に新しい variant が増えたら TypeScript がコンパイル時に拒否
するよう型レベルで縛る。`HubWire.ts` の型定義に手を入れた瞬間ここが赤くなるので、
ハンドラ追加の漏れが build で止まる (= 設計と実装の drift を機械検出)。

##### 6.2.2.8 `send*` メソッドの boolean 戻り値契約

`sendReply` / `sendPermission` / `sendPermissionAbort` の `boolean` 戻り値は以下の意味:

| 戻り値 | 意味 | 経路 |
|---|---|---|
| `true` | ack 後なら write 成功 (kernel buffer 到達)、ack 前なら queue 積み成功 | `writeNow` true / `enqueueOrSend` の queue path |
| `false` | socket 不在 (`closed === true` or `socket === null`) で drop | `enqueueOrSend` の `no_socket` 分岐 / `writeNow` の destroyed 判定 |

呼び出し側は `false` を受けたら caller のレイヤで握る (例: §6.2.1.6 `reply` tool で
`isError` を返して Claude に伝える)。backpressure (`!sock.write()` の戻り false) は
`true` 扱い + warn ログ — kernel buffer には乗っているため drop ではない。`writeFailed`
の例外 path も `false` に倒す (write 系の sync throw 経路)。

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

##### 6.2.3.1 fail-fast (env 不在 / 不正 UUID)

env 未設定 / 空 / UUID 正規表現に合致しない値はすべて即 throw する。`crypto.randomUUID()`
等で fallback する誘惑があるが、絶対に許さない:

- random UUID で起動すると claude の `~/.claude/projects/<slug>/<uuid>.jsonl` (起動引数の
  `--session-id` 由来) と Hub `register` の session_id が **黙って乖離** する
- Phone は session_active を受けて「正常」と認識するが、Claude 履歴とは無関係の幽霊
  session が Hub に register される
- AD-12 (session_id 相関) の前提が破壊され、再 produce / debug が極めて難しいバグになる

error メッセージは「wrapper 経由 (`claude-mobile-hud run safe|yolo`) で起動せよ」を
明示し、誤起動の修復経路を runtime に示す。

##### 6.2.3.2 `processEnv` test seam

`SessionDetectorOptions.processEnv` で env 源を差し替え可能にする。`bridge/test/unit.test.ts`
で「env 未設定 / 不正 UUID / 正常 UUID」のケースを deterministic に検証するため
(`process.env` を直接書き換えると並列テストが干渉する)。default は `process.env`。

#### 6.2.4 `ImageStaging`

Phone から base64 画像を受け取ったら local file に書き出し、Claude に `meta.image_path`
で渡す (FR-PH-64 / AD-09)。`save()` は staged file の絶対パスを返し、複数 Bridge プロセス
が同居しても安全な per-pid inbox 設計にする。

##### 6.2.4.1 per-pid inbox レイアウト

inbox の default path は `~/.claude/channels/mobile-hud/inbox/<pid>/` (`ImageStaging.
defaultPath()`)。`<pid>` 層を 1 段挟む理由:

- 同一 user で複数 `claude-mobile-hud run` を並列に起動した場合 (現行は単一 session 前提
  だが、defense in depth)、各 Bridge が独立した directory に書き出すので uuid 衝突や
  cleanup の race が無くなる
- Bridge 終了時の cleanup は **自 pid の inbox を丸ごと rm** で済む (個別 file 削除
  リストを持たない)

##### 6.2.4.2 起動時 orphan inbox の GC (P2-7)

`prepare()` で自 inbox を mkdir した直後に、`<inboxRoot>/` 直下を 1 回だけ readdir し、
`<pid>` 命名のうち生きていない pid の directory を `rm -rf` する。kill -9 や crash で
通常 cleanup を逃した残骸 (古い session の画像) を毎起動で回収する目的。

- 自分の pid と一致する name は skip
- 整数 parse できない / 数字に再正規化したら形が変わる name (`String(parseInt(name))
  !== name`) は **触らない** (typo や手動配置 directory を誤消去しないため)
- 個々の rm 失敗は warn ログのみで継続 (1 件失敗で他の cleanup が止まらない)

##### 6.2.4.3 MIME → 拡張子 whitelist (AD-09)

| MIME | 拡張子 |
|---|---|
| `image/jpeg` | `jpg` |
| `image/png` | `png` |
| `image/webp` | `webp` |
| `image/gif` | `gif` |

IANA 公式 4 種に限定。これ以外 (`image/heic` 等) は `isSupportedMime` で false を返し、
`save()` が `unsupported mime` で throw する。Phone 側 ImageProcessor が JPEG/PNG/WEBP に
正規化済 (§3.2.7) なので通常は通る。

##### 6.2.4.4 `defaultIsPidAlive` の EPERM 扱い

`process.kill(pid, 0)` は signal 0 = 「実際に送らず存在/権限のみ check」。例外コード
の意味が複数あり、誤判定を避けるために以下にまとめる:

- 例外なし → pid 存在、生きている → `true`
- `ESRCH` → pid 不在 → `false`
- `EPERM` → pid 存在するが kill 権限なし (= 別 user で起動された Bridge 等) → `true`

`EPERM` を `true` 扱いにする理由は、「存在するが触れない」 inbox を消すと別 user が
書き込み中の file を踏むため。orphan GC は同 user の dead pid のみを掃除する。

##### 6.2.4.5 `isPidAlive` は test seam

`ImageStagingOptions.isPidAlive` で生存判定を差し替え可能にする。`bridge/test/unit.test.ts`
で「人工的に dead pid directory を作って GC が消すか」を deterministic に検証するため
(実プロセスを fork して `kill -9` する代わり)。default 実装 (`defaultIsPidAlive`) は
省略時のみ自動採用。

### 6.3 Bridge 終了時の挙動

- Claude が die → Bridge も die (stdio close)
- Hub 側 `BridgeServer` が socket close 検出 → `OutstandingPermissions.onBridgeDisconnected(bridgeSessionId)` 発火
- Phone に `permission_abort` が自動 push (FR-HU-13)

### 6.4 Bridge bootstrap / shutdown wiring (`bridge/src/index.ts`)

Bridge は Claude Code から `--mcp-config` 経由で stdio 子プロセスとして起動される。
entry (`bridge/src/index.ts`) は以下の決まった順序で初期化する。

#### 6.4.1 起動順序

```
loadConfig() → StructuredLog(root) → SessionDetector.detect()
            → ImageStaging.prepare()
            → HubClient.connect() (register + ack 待ち、§6.2.2.2)
            → McpServer.start() (stdio MCP)
            → SIGTERM/SIGINT handler 登録
```

- `loadConfig`: env `HUB_HOST` / `HUB_BRIDGE_PORT` / `BRIDGE_INBOX_DIR` /
  `BRIDGE_LOG_LEVEL` を読む。inbox は未指定なら `ImageStaging.defaultPath(pid)`
- `SessionDetector.detect()` が **必ず** `HubClient.connect()` より先に走ること:
  session_id が無いまま Hub と話し始めると register が壊れる
- `HubClient.connect()` の resolve は register write 完了で返る (ack は §6.2.2.2 で
  別経路非同期 flush)

#### 6.4.2 `hub` / `mcp` を `let null` で先宣言 (P1-4)

`HubClient` の `callbacks.onClose` / `onSend` / `onPermissionVerdict` は connect 後の
socket event で発火し、callback の中で `mcp` と `shutdown` を参照する。これらは
`HubClient` を **構築 / connect した後に** bind される変数なので、`const hub = new
HubClient({ callbacks: { onSend: (m) => mcp.deliverSend(m), ... } })` のような書き方で
クロージャ内の `mcp` を直接参照すると、TDZ (temporal dead zone) で参照不可な変数を
クロージャが捕捉する事故になる。

対策: `let mcp: McpServer | null = null` / `let hub: HubClient | null = null` で
**先に null 宣言**してからインスタンスを代入し、callback 内では `mcp?.deliverSend(msg)`
のように optional chain で参照する。これで「callback 発火が代入より先に起きても null
で握り潰す」defense in depth と、クロージャの bind 順序問題を 1 つの定石で潰す。

#### 6.4.3 `shutdown` の idempotency と signal handler 二方向

`shutdown(signal)` は `shuttingDown` flag で **多重発火を抑止** (`SIGTERM` と
`mcp_close` が同時に届くケースが現実にある — Claude の終了経路は OS signal と stdio
close が並走する)。発火順序:

1. `shuttingDown = true` (再入防止)
2. `mcp?.close()` (stdio 切断)
3. `hub?.close()` (TCP 切断 = Hub 側で FR-HU-13 一括 abort が走る、§5.2.3.7)
4. `await images.cleanup()` (per-pid inbox rm)
5. `process.exit(0)`

handler 登録は `SIGTERM` と `SIGINT` の 2 つに加え、`hub.onClose("remote")` /
`mcp.onClose` の 2 経路。合計 4 入口で同じ `shutdown` を idempotent に呼ぶ。

#### 6.4.4 hub remote close → Bridge 自殺 (§5.3 と対)

`HubClient.onClose(reason)` が `"remote"` を返したとき (= Hub が TCP 切断した) は
`shutdown("hub_remote_close")` を呼んで自殺する。`"local"` (= 自分から close した)
ときは shutdown を**呼ばない** (= shutdown 経路から `hub.close()` を呼んだ後の close
event で再帰しないため)。Hub crash 後の運用 (Bridge も自殺 → claude が MCP server 不在で
stuck → user が wrapper を Ctrl-C → 再起動) は §5.3 を参照。

#### 6.4.5 fatal error 経路

`main().catch()` で `process.stderr.write` (= structured log を経由しない) で stack
を吐き、`process.exit(1)` で終わる。Logger は init 失敗時点で利用不能な場合があるので
stderr 直書きにする (= bootstrap 失敗の最後の砦)。

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

### 8.5 TypeScript `StructuredLog` 実装詳細

`hub/src/log/StructuredLog.ts` と `bridge/src/log/StructuredLog.ts` は同一実装の
コピー (両モジュール間で type 共有しないため意図的に独立)。出力形式は §8.3 / §8.4 に
従う。

#### 8.5.1 stderr 専有 (stdout の用途分離)

`sink` の default は `process.stderr.write(line + "\n")`。stdout は経路ごとに異なる
専用用途に割当 (Bridge → MCP プロトコル / Hub → 将来の IPC 拡張余地) しており、ログを
stdout に混ぜると Bridge の MCP framing を壊す (§6.2.1.1) / Hub の将来の IPC を縛る
ため絶対禁止。

#### 8.5.2 `sink` 関数 seam

constructor 第三引数 `sink: (line: string) => void` で出力先を差し替えられる。テスト
時に lines を array に push してから assertion に流す目的 (`bridge/test/unit.test.ts`
や Hub テストの想定 seam)。default を指定しているので本番経路は無改変。

#### 8.5.3 `withTag` の cascade

`new StructuredLog("hub", "INFO").withTag("http").withTag("verdict")` で
`tag=hub.http.verdict` の dot 区切り階層タグを cascade できる。Hub / Bridge entry は
root logger を作って、各 server / module に `.withTag(...)` で派生 logger を渡す
パターンを統一する (`hub/src/index.ts:30`, `bridge/src/index.ts:42` 等)。

#### 8.5.4 `NEEDS_QUOTE` で必要時のみ quote

regex `/[\s"=\\\x00-\x1f\x7f]/` のいずれかを含む値は `JSON.stringify` で完全 escape
する。対象:

- 空白 (space / tab / newline / CR / form feed / vertical tab)
- 引用符 `"`
- `=` (key=value 区切り)
- `\` (backslash escape)
- 制御文字 `\x00-\x1f` / `\x7f`

これ以外の素直な英数値は **裸で書く**: `chat_id=abc123` のように 1 行が読みやすく、
parser (`tools/verify_atomicity.py` の `parse_kv_line`) も `split('=', 1)` 1 回で済む。

#### 8.5.5 `null` / `undefined` を string として区別

`v === null` → `"null"`、`v === undefined` → `"undefined"` を返す (両者を空文字に
潰さない)。理由: `parent_id=null` と `parent_id=undefined` と `parent_id=` (= 不在) は
それぞれ「明示的に null」「明示的に未定義」「フィールドそのものが省略」の意味で
**異なる**。AC-09 検証ランナーは `dict[key] == 'null'` で文字列比較するため、
空文字に潰すと「不在」と区別が付かなくなる (§8.3 の `pending_request_id=null` 例参照)。

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

### 9.3 dispatcher wrapper (`claude-mobile-hud`)

repo 直下の bash dispatcher。Hub / pair / run / resume / list-sessions /
rotate-token を 1 つの CLI entry に集約する。`set -euo pipefail` で fail-fast。

#### 9.3.1 サブコマンド一覧と委譲先

| サブコマンド | 委譲 / 役割 |
|---|---|
| `hub` | `npm --prefix hub start` を exec (foreground Hub daemon) |
| `pair lan\|ts` | `npm --prefix hub run pair:<mode>` を exec (§5.5) |
| `run safe\|yolo` | `.mcp.runtime.json` 動的生成 + `exec claude --mcp-config ...` (AD-12) |
| `resume` | cwd 配下の `*.jsonl` から interactive 選択 → `run safe` と同じ wiring + `--resume` |
| `list-sessions` | cwd-encoded directory を `~/.claude/projects/<encoded>` で参照 |
| `rotate-token` | `hub/.env` の `HUB_TOKEN` を `openssl rand -hex 16` で再生成 (in-place, Hub 自動 restart しない) |
| `help` / `*` | usage / unknown command |

`hub` / `pair` は前段で `hub/node_modules` の存在を check し、未インストール時に
actionable な error (`npm --prefix hub ci`) を吐く。

#### 9.3.2 `run` の wiring (AD-12 single source of truth)

session_id は wrapper が `uuidgen` で 1 つ生成し、**同じ UUID を 2 方向に push** する
(§6.2.3 env injection と対):

1. `claude --session-id <uuid>` で claude の履歴 slug を確定する
   (`~/.claude/projects/<slug>/<uuid>.jsonl`)
2. `.mcp.runtime.json` の `env.BRIDGE_SESSION_ID=<uuid>` で Bridge に inject

これにより Bridge が `/proc` を歩かず env を読むだけで session_id が確定する。

**`--dangerously-load-development-channels server:channel`** は Bridge が emit する
`notifications/claude/channel*` を claude に届ける gate flag。Claude Code 2.x で
`--help` から hidden になったが flag 自体は受理される。`server:` の後ろの `channel`
は `.mcp.runtime.json` の `mcpServers.channel` と一致させる契約。

`yolo` モードは `--dangerously-skip-permissions` を追加で渡す (= Permission Relay 経由
を無効化)。`safe` モードは何も追加しない (= Bridge が permission elicitInput を Hub に
転送する経路、§3.2.1.2)。

#### 9.3.3 `.mcp.runtime.json` の動的生成 (P1-1)

`--mcp-config` で渡す MCP 設定を毎回 wrapper が生成する。user-scope (`~/.claude.json`)
の MCP entry には依存しない self-contained 構成にする狙い (= user の global config を
汚さない、bridge に tsx 同梱なので `npx -y` 不要)。

heredoc に `$TSX_BIN` / `$BRIDGE_ENTRY` を直接埋め込むと、これらのパスに `"` / `\`
が混じった瞬間 JSON が壊れる (現状は固定パスで実害無いが防御コード)。`node -e
'fs.writeFileSync(... JSON.stringify({...}))'` で escape を Node の JSON 処理系に任せる
方式を取る。

```json
{"mcpServers":{"channel":{"type":"stdio","command":"<tsx>","args":["<entry>"],"env":{"BRIDGE_SESSION_ID":"<uuid>"}}}}
```

- `"type": "stdio"`: Claude Code 2.x は省略時の type 推測が不安定なので明示
- runtime 生成 path は `bridge/.mcp.runtime.json`。並列 `run` は未サポート (上書き
  race、§6.2.3 scope 外)

#### 9.3.4 必須コマンドの前段 verify (P2-1)

`run` / `resume` で Hub 起動 check に `nc -z -w 1 127.0.0.1 $HUB_BRIDGE_PORT
2>/dev/null` を使うが、`nc` 不在環境では `command not found` を `2>/dev/null` が握り
潰し、後段が「Hub not running」という misleading なメッセージを吐く。これを防ぐため
`for bin in nc uuidgen claude node` を先に `command -v` で verify し、不足を
actionable な error (`install it or check PATH`) として吐く。Ubuntu では default で
全て入るので通常は silent path。

#### 9.3.5 `resume` の差分

`run safe` と同じ wiring (`.mcp.runtime.json` + `exec claude`) を共有するが:

- session_id を新規生成せず、cwd 配下の既存 `*.jsonl` から interactive 選択した uuid を
  再使用
- `--session-id $target` に加えて `--resume $target` を渡す (claude の resume path)
- file リストは `ls -1t` で mtime 降順、UI は `[N] MM-DD HH:MM uuid` を stderr、
  prompt が `Pick [1-M] (default 1):` (空入力で先頭を選択)

#### 9.3.6 `list-sessions` の cwd encoding

claude のローカル規約に従い、cwd の `/` を `-` に置換した文字列 を `~/.claude/projects/`
配下の directory 名として参照する (`${CWD//\//-}`)。directory 不在時は `(expected
dir: ...)` を併記して原因がわかる error を吐く。出力 1 行 = `MM-DD HH:MM  size  uuid
preview` (preview は jsonl 1 行目から `message.content[0].text || content || text` を
60 文字に切る best-effort)。

#### 9.3.7 `rotate-token` の non-destructive 運用

- `hub/.env` を sed in-place で書き換える (`HUB_TOKEN=...` 行のみ、行が無ければ末尾
  追加)
- sed delimiter は `|` (新 token に `&` が含まれる場合の置換破綻を回避)
- **Hub プロセスを自動 restart しない**: 現実行中の session を巻き込まないため。
  代わりに `pgrep -f hub/src/index.ts` で Hub 動作中なら user に restart を促すメッセージ
- Phone Settings の token も再 pair が必要、と stderr で明示する

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
