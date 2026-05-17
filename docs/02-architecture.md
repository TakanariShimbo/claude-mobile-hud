# Phase 2: 基本設計 (アーキテクチャ)

`claude-mobile-hud` v1.0 の **外部設計**。コンポーネントの責務 / 境界 / interface (wire protocol) / 主要シーケンス / 横断的設計判断 を定める。各コンポーネントの**内部実装** (クラス / メソッド) は Phase 3 (詳細設計) で扱う。

- **作成日**: 2026-05-16
- **改訂**: Rev 5 (Phase 3 AD-13 で導入した `permission_snapshot` wire を §4.3.1 / §4.7 に正式登録)
- **依存**: Phase 1 (`01-requirements.md`)
- **対象範囲**: Phase 1 §11.2 (Phase 2 設計判断) への結論 + §11.1 (要件穴) の最終値確定

---

## 1. 目的と Phase 1 からの引き継ぎ

### 1.1 この文書の役割

Phase 1 で「何を作るか」が決まった。本文書では「どう構造化するか」を以下のレベルまで定める:

- コンポーネント間の責務分担と境界
- コンポーネント間で流れるデータ (wire protocol) — **送信側 / 受信側両方の規約**
- 主要シナリオがコンポーネント間でどう実現されるか (正常系 + エラー path)
- 横断的設計判断 (AD-NN として番号付け)
- 構造的負債 (mode race / FGS 結合 / wire 二重定義) を**設計レベルで先回りして避ける**

クラス図 / メソッドシグネチャ / 各 algorithm の実装詳細は **Phase 3** に送る。

### 1.2 Phase 1 §11.1 (要件穴) への確定値

| 項目 | 確定値 | 根拠 |
|---|---|---|
| NFR-40a (Glass 未接続 8h 電池) | **< 10%** 維持 (実機計測で見直し可) | 同条件相当の常駐 FGS が許容範囲となる経験則 |
| NFR-40b (Glass 接続 8h 電池) | **< 25%** 維持 (実機計測で見直し可) | BT + マイク待機ぶんの加算 |
| NFR-41 (履歴サイズ上限) | **20 MB / session, 200 MB 全体**。LRU 退避 | 画像 200KB × 100 件相当 / 個人ツールとして 1 ヶ月程度の履歴を想定 |
| マルチセッション数上限 | **ハード制限なし**。NFR-41 (200 MB 全体) のソフトキャップに自然に収まる | 個人ツールで session 5 個を超える運用は稀 |
| Glass 接続未確立しきい値 (FR-GL-05) | **15 秒** | Hi Rokid のブローカー初期化に最大 10 秒程度。余裕を見て 15 秒 |

### 1.3 Phase 1 §11.2 (設計判断) への結論

各項目は本文書の対応セクションで詳述。ここはサマリ。

| 判断項目 | 結論 | 詳細 |
|---|---|---|
| wire protocol の物理表現 | **Phone↔Glass: Caps バイナリ (SDK 要求)、Phone↔Hub: JSON (HTTP/SSE)、Hub↔Bridge: JSON NDJSON (loopback)**。**全て同一の論理スキーマから派生** | §4, AD-01 |
| shared module の物理形態 | **Kotlin library module `:protocol`** (Phone / Glass で共有)。Hub/Bridge (TS) は手書き型 + **双方向 parity test** | §4.2, AD-02 |
| mode + payload bundling | **`current_state` wire イベントに mode と全 payload を bundle (`seq` 単調増加)**、サブイベントは `parent_seq` を持ち親 state に紐付け、immutable swap で受信側 atomicity も保証 | §4.5, AD-03 |
| 再接続バックオフ | **Exp backoff 1s→30s (factor 2, cap 30s, jitter ±25%)**。Hub↔Phone, Phone↔Glass の双方 | §7.4, AD-04 |
| ID 生成 | **session_id: Claude Code、chat_id: Hub、request_id: Claude/Bridge**。全て UUID v4 形式 | §4.6 |
| 履歴削除トリガー | **書込時 LRU**。`updated_at` の古い session から削除 | §7.6 |
| テストの粒度 | **wire decode / state machine / 純関数は unit、結合は wire レベル、UI は実機**。NFR-13 (atomicity) は**自動検証手段あり** | §7.7 |
| FGS オーケストレータの形態 | **`AppLifecycleController` object** + **明示的な state machine** | §3.2, §7.5, AD-05 |
| エラーモデル (Rev 2 追加) | **sealed `WireError` + 層責務 (Repository 分類 → UI 表示)** | §7.8, AD-06 |
| Token 寿命 (Rev 2 追加) | **X-Token: 永続、CLI `rotate-token` で再生成。Glass token: Hi Rokid 任せ、Phone 側 UI で revocation** | §2.4, §7.9, AD-07 |
| 多重起動戦略 (Rev 2 追加) | **Hub: ポート占有検知。Phone: singleTask + singleTop** | §7.10, AD-08 |
| 画像処理仕様 (Rev 2 追加) | **入力時 Phone UI 層で max 1280px / JPEG 80% / EXIF 全除去** | §7.11, AD-09 |
| PendingPermission キュー (Rev 2 追加) | **(現 session 優先) + (created_at 昇順) のソート、現 session の先頭を UI 表示** | §7.12, AD-10 |
| i18n (Rev 2 追加) | **v1.0: 日本語固定だが文字列リソース化、将来 en 追加は機械的に可能** | §7.13, AD-11 |
| 観測性 / 相関 ID (Rev 2 追加) | **構造化ログ (key=value 形式)、chat_id / request_id / session_id を Phone→Hub→Bridge→Claude の経路で透過伝播** | §7.14, AD-12 |

---

## 2. システム全体像

### 2.1 コンポーネント

```
                                                ┌─────────────┐
                                                │ Claude Code │
                                                └──────┬──────┘
                                                  MCP stdio
                                                       │
                                                ┌──────▼──────┐
                                                │   Bridge    │  (TS, per-session)
                                                └──────┬──────┘
                                              TCP NDJSON loopback
                                                       │
                                                ┌──────▼──────┐
                                                │     Hub     │  (TS, daemon)
                                                └──────┬──────┘
                                                  HTTP / SSE
                                                       │
                                  ┌────────────────────▼────────────────────┐
                                  │            Phone app (Kotlin)            │
                                  │  ┌─────────┐ ┌─────────┐ ┌─────────┐    │
                                  │  │Channel  │ │Glass    │ │Mic FGS  │    │
                                  │  │Service  │ │Connect  │ │         │    │
                                  │  │FGS      │ │Service  │ │         │    │
                                  │  └─────────┘ │FGS      │ └─────────┘    │
                                  │              └─────────┘                  │
                                  │           ┌──────────────────────┐       │
                                  │           │ AppLifecycleController│      │
                                  │           │ (FGS オーケストレータ) │      │
                                  │           └──────────────────────┘       │
                                  └────────────────────┬────────────────────┘
                                                       │
                                              CXR-L (Caps binary)
                                              + Bluetooth SCO/LE Audio (音声)
                                                       │
                                                ┌──────▼──────┐
                                                │ Glass app   │  (Kotlin)
                                                └─────────────┘
```

### 2.2 責務マトリクス

| コンポーネント | 主責務 | 状態の所有 | 持たないもの |
|---|---|---|---|
| **Phone app** | 入力 / 履歴 / permission / セッション管理 / Glass relay | `current_session`, `messages`, `pending_permissions`, `transcript_state`, `input_text`, `current_mode`, `current_state.seq` | Claude session そのもの (PC 側にある) |
| **Glass app** | HUD 表示 + ジェスチャ + 音声入力 | `cursor_position` (sendChoice / permissionChoice の選択カーソルのみ), `lastSeenSeq` (stale ドロップ用) | mode 決定ロジック (Phone が真実源) |
| **Hub** | Phone との通信中継 / Bridge ルーティング / chat_id 発行 / token 検証 | アクティブ session 一覧, request_id ↔ bridge map | 履歴 (Phone が source of truth) |
| **Bridge** | Claude ↔ Hub の MCP 仲介 / reply tool / 画像引き渡し | (process scope のみ — 自分の session の状態) | Cross-session 状態 |

**重要**: Phase 1 NFR-13 (atomicity) は **「mode 決定は Phone のみで行い、Glass は wire で受信して表示するだけ」 + 「current_state は 1 wire + seq カウンタ」+「受信側 immutable swap」** という三層の責務分担で保証する。詳細 §4.5, §7.1。

### 2.3 ネットワーク境界

| 境界 | 物理層 | 認証 | 暗号化 |
|---|---|---|---|
| Phone ↔ Hub | TCP/HTTP over LAN または Tailscale | X-Token ヘッダ (≥128 bit) | なし (LAN / Tailscale 前提) |
| Hub ↔ Bridge | TCP NDJSON over loopback (127.0.0.1) | なし (loopback 境界自体が壁) | なし |
| Bridge ↔ Claude | stdio (parent-child) | プロセス境界 | N/A |
| Phone ↔ Glass | CXR-L (Caps binary, Bluetooth 経由) | Hi Rokid 発行 token | CXR-L SDK 任せ |
| Glass mic → Phone | Bluetooth SCO / LE Audio | BT pairing 認証 | BT 層 |

### 2.4 認証の寿命と revocation (AD-07 サマリ。詳細 §7.9)

| 認証 | 発行 | 保管 | 失効 | 再交付 |
|---|---|---|---|---|
| X-Token (Phone↔Hub) | Hub 起動時に永続値を `.env` に保存。CLI `claude-mobile-hud rotate-token` で再生成 | Phone: DataStore (平文), Hub: `.env` (ファイル権限) | rotate-token 実行で旧 token 無効化 | QR 再スキャン |
| CXR-L token (Phone↔Glass) | Hi Rokid アプリ経由 | Phone: EncryptedSharedPreferences | Phone UI「認可を解除」 | Hi Rokid 認可フロー再実行 |
| OpenAI API Key | ユーザが設定で投入 | Phone: DataStore (平文) | UI で削除 | UI で再入力 |

**端末紛失時運用**: Phone 紛失なら PC 側で `claude-mobile-hud rotate-token` を実行 → 旧 token は即座に無効化される。新 Phone で QR 再ペアして復旧。Glass token は Hi Rokid 側 (Rokid アカウント) で revocation 可能 (本プロジェクト範囲外)。

---

## 3. プロセスとライフサイクル

### 3.1 プロセス一覧

| プロセス | 寿命 | 起動者 | 停止条件 |
|---|---|---|---|
| Hub daemon | 長寿命 (PC 起動中常駐) | ユーザが手動で起動 (`claude-mobile-hud hub`) | ユーザが手動停止 / PC 再起動 |
| Bridge | 1 Claude session に 1 つ | Claude Code が `--mcp-config` 経由で生成 | Claude session 終了で die |
| Phone Activity (`MainActivity`) | ユーザの能動利用中 | ランチャタップ | バックグラウンド遷移後 OS 任せ |
| Phone ChannelService (FGS, remoteMessaging) | Phone app 起動中ずっと | `AppLifecycleController.startChannel` | `stopChannel` / `shutdownAll` |
| Phone GlassConnectionService (FGS, dataSync) | Glass ペアリング接続中 | `AppLifecycleController.startGlassSession` | `stopGlassSession` / 自然切断 / `shutdownAll` |
| Phone MicForegroundService (FGS, microphone) | GlassConnection と同寿命 | `AppLifecycleController.startGlassSession` | 同上 |
| Glass Activity | ユーザ利用中 | ランチャ or Phone からの `appStart` | ユーザ離脱 |

### 3.2 FGS オーケストレーション (AD-05)

FGS 同士の直接結合 (例: `GlassConnectionService` から `MicForegroundService.start/stop` を呼ぶような形) は責務境界を曖昧にするため避ける。設計の原則は **FGS 単体は他 FGS の存在を知らない**。代わりに `AppLifecycleController` (Phone app の object) が "用途" 単位の起動/停止を提供する。

#### 3.2.1 公開 API (Phase 3 で署名確定)

| API | 起動する FGS 群 | 冪等性 |
|---|---|---|
| `startChannel(context)` | ChannelService | 既に Running なら no-op |
| `stopChannel(context)` | ChannelService | 既に Off なら no-op |
| `startGlassSession(context)` | GlassConnectionService + MicForegroundService | 既に Running なら no-op、Starting 中なら待機後 no-op |
| `stopGlassSession(context)` | GlassConnectionService + MicForegroundService | 既に Off なら no-op |
| `shutdownAll(context)` | 全 FGS | 並列に stop、両方 Off まで待つ |

UI / ExitDialog / Glass 接続ダイアログ等は **この controller のみを叩く**。FGS 同士は互いに知らない。

#### 3.2.2 State Machine

**ChannelService の状態**:

```
[Off] --startChannel--> [RunningChannel] --stopChannel/shutdownAll--> [Off]
```
シンプルに 2 状態。

**Glass+Mic FGS の状態** (Glass 自然切断との競合を吸収するため明示的に持つ):

```
        startGlassSession             Glass FGS onCreate
[Off] ─────────────────────> [Starting] ───────────────> [Running]
  ▲                              │                          │
  │                              │ 起動失敗                  │ stopGlassSession
  │ 停止完了                      ▼                          │ or 自然切断
  └─────────────────── [Stopping] <───────────────────────┘
                          │
                          │ shutdownAll が並列で叩かれた場合は state を奪わず stopGlassSession 完了を待つ
                          ▼
                       [Off]
```

**重要規則**:
- `startGlassSession` を `Starting` / `Running` 中に呼んでも no-op (冪等)
- `stopGlassSession` を `Off` / `Stopping` 中に呼んでも no-op
- Glass 自然切断 (CXR-L disconnect) は `GlassConnectionService` から `AppLifecycleController.onGlassDisconnected()` で通知 → state を `Stopping` → Mic FGS も停止 → `Off`
- ユーザが切断中に再接続を試行した場合: `Stopping` 完了まで待ってから `Starting` に遷移 (中断しない)
- 状態は `StateFlow<GlassFgsState>` で expose し、UI から監視可能

### 3.3 状態の永続化 / 揮発の境界

| 状態 | 永続化先 | 起動時の復元 |
|---|---|---|
| 設定 (baseUrl, token, openAiApiKey) | Phone DataStore | 自動 |
| Glass token | EncryptedSharedPreferences | 自動 |
| 履歴 (messages, sessions) | Phone ローカルファイル (JSON, Phase 3 で sqlite 検討) | 自動 (NFR-12) |
| current_session | 履歴と一緒に永続化 (last activity の session を復元) | 起動時 reconciliation あり (§6.5) |
| current_mode | 揮発 (起動時 IDLE) | しない (NFR-13 整合) |
| pending_permissions | 揮発 (Hub が持つ → 再接続時に再 push) | Hub 側から再取得 |
| transcript / input_text | 揮発 | しない |
| `current_state.seq` | 揮発 | 起動時 0 から (§4.5) |

---

## 4. Wire Protocol

### 4.1 全体方針

- **論理スキーマは 1 つ**。物理表現 (Caps / JSON) はチャネル都合で派生する
- **論理スキーマは本文書 §4.3 を単一の真実源** とする
- Phone (Kotlin) + Glass (Kotlin) は共有 module `:protocol` で同じ型定義を参照
- Hub / Bridge (TypeScript) は手書きで対応する型を維持し、CI で**双方向 parity test** を回す (§4.2, AD-02)
- NFR-50 (drift コンパイル / CI 検出) は AD-02 で達成
- 各レイヤで相関 ID (chat_id / request_id / session_id) を**透過伝播** (AD-12)

### 4.2 Shared module `:protocol` (AD-02)

```
:protocol (Kotlin library, JVM)
├── WireEvent.kt        ← sealed interface (全イベントの論理型)
├── WireField.kt        ← フィールド名定数 (snake_case)
├── codec/
│   ├── CapsCodec.kt    ← WireEvent ↔ Caps (Glass app で使用)
│   └── JsonCodec.kt    ← WireEvent ↔ JSON (Phone↔Hub で使用、kotlinx.serialization)
└── test/
    ├── KotlinGolden.kt ← 全 WireEvent を JSON 文字列化 → `golden/kotlin/*.json` に保存
    └── TsGoldenVerify.kt ← `golden/ts/*.json` (TS 側出力) を Kotlin decode して assert equal
```

TS 側にも同様の golden ペアを置く。**Kotlin encode→TS decode** と **TS encode→Kotlin decode** の**両方向**を CI で green に保つ。

### 4.3 イベント一覧 (論理スキーマ)

#### 4.3.1 Hub ↔ Phone (HTTP/SSE)

##### Phone → Hub (HTTP POST)

| エンドポイント | 用途 | リクエスト | レスポンス |
|---|---|---|---|
| `POST /send` | テキスト/画像送信 | `{ "text", "session_id"?, "image_base64"?, "image_mime"? }` | `200 { "chat_id", "session_id"? }` / `4xx { "error_code", "message" }` |
| `POST /permission` | permission verdict | `{ "request_id", "behavior": "allow"\|"deny" }` | `200 OK` / `410 Gone` (abort 先勝ち / unknown request_id) |

##### Hub → Phone (SSE GET `/events`)

各イベントは SSE の `event: <type>` + `data: <json>` で送る。

| event type | data shape | 説明 |
|---|---|---|
| `reply` | `{ "chat_id", "session_id"?, "text" }` | Claude からの reply |
| `permission` | `{ "request_id", "session_id"?, "tool_name", "description", "input_preview" }` | ツール承認要求 |
| `permission_abort` | `{ "request_id", "reason"? }` | Claude 側で abort された permission の cleanup |
| `session_active` | `{ "session_id" }` | session が起動 / アクティブに |
| `session_inactive` | `{ "session_id" }` | session が終了 |
| `session_snapshot` | `{ "active_session_ids": [...] }` | SSE 接続成立直後に **必ず 1 回**送る (起動時 reconciliation 用、§6.5) |
| `permission_snapshot` | `{ "request_ids": [...] }` | SSE 接続成立直後、`session_snapshot` の後に **必ず 1 回**送る (AD-13、createdAtMs 昇順)。Phone は受信した request_id 集合で local `pendingPermissions` を絞り込む |

備考: SSE プロトコルの open/close は wire 上のイベントではなく接続層で扱う。`session_active` / `session_inactive` は冪等 (同 id を複数回受信しても OK)。

#### 4.3.2 Hub ↔ Bridge (TCP NDJSON loopback, port 8787 default)

接続後、Bridge は最初に `register` を送る。以後 NDJSON (1 行 1 JSON オブジェクト) で双方向通信。

##### Bridge → Hub

| `type` | フィールド | 説明 |
|---|---|---|
| `register` | `session_id`, `pid` | Bridge 起動時の自己紹介。Hub はこれで session_id ↔ bridge socket を map |
| `reply` | `chat_id`, `session_id`, `text` | Claude → Phone の返信 (reply MCP tool の発火結果) |
| `permission` | `request_id`, `session_id`, `tool_name`, `description`, `input_preview` | Claude 側 permission 要求 |
| `permission_abort` | `request_id`, `reason`? | Claude 側で permission が中止された |

##### Hub → Bridge

| `type` | フィールド | 説明 |
|---|---|---|
| `ack_register` | `chat_id_seed`? | 登録成功通知 (chat_id 生成の seed 受け渡しは Phase 3 で要否判断) |
| `send` | `chat_id`, `text`, `image_base64`?, `image_mime`? | Phone → Claude のメッセージ。Hub が chat_id を mint してから渡す。画像は base64 のまま Bridge へ転送し、Bridge 側で staging (§6.2.4 と整合)。Rev 6 で `image_path?` から訂正 |
| `permission_verdict` | `request_id`, `behavior` | Phone の verdict 結果 |

##### 接続管理

- Bridge から Hub へ向けて connect (Bridge spawn 時に Claude Code が指定したポートに繋ぐ)
- 切断 = session 終了。Hub は session_id を inactive 化し SSE で `session_inactive` を Phone に push
- Hub 起動時に既存 Bridge は再接続 (Bridge 側の再接続ループは Phase 3 で詳細)

#### 4.3.3 Phone ↔ Glass (CXR-L Caps)

##### Phone → Glass

| event | フィールド | 説明 |
|---|---|---|
| `session_open` | `ts` | CXR 接続成立 |
| `session_close` | `ts` | CXR 切断 |
| `ping` | `ts` | heartbeat (5s 間隔、12s 受信無しで session_open リセット) |
| **`current_state`** | `seq`, `mode`, `pending_permission?`, `transcript_state`, `transcript_text`, `input_text`, `mic_source` | **mode 決定に関わる全 payload を 1 wire で bundle + 単調増加 seq** (AD-03)。`mic_source = GLASS \| PHONE_FALLBACK` (BT SCO 失敗時の表示用) |
| `input_text_only` | `parent_seq`, `input_text` | **input_text の単独高頻度更新専用**。`parent_seq` = 直前の `current_state.seq`。受信側で `parent_seq == lastSeenStateSeq` のときだけ apply (AD-03 巻き戻し race 防止) |
| `session_list` | `sessions` (JSON 配列) | アクティブ session 一覧 |
| `current_session` | `id` | 現在 session 切替 |
| `messages` | `session_id`, `messages` (JSON 配列) | 現在 session の履歴スナップショット |
| `notification` | `kind` ("reply"/"permission"), `text`, `session_id?` | HUD banner 用の一時通知 |
| `error` | `message` | Phone 側エラー (デバッグ用) |

##### Glass → Phone

| event | フィールド | 説明 |
|---|---|---|
| `hello` | `ts` | Glass プロセス起動 / Phone への state 再 push 要求 |
| `select_session` | `id` | session 切替 |
| `gesture` | `which` ("tap"/"double_tap"/"swipe_forward"/"swipe_back") | ジェスチャ通知 (現 mode に応じて Phone 側で意味解釈) |
| `listening_cancel` | (none) | Listening 中の DoubleTap = 録音停止 + 入力クリアを **atomic に** 表現 (2 連送信に分けると中間 state が漏れるため専用 wire を用意) |
| `permission_verdict` | `request_id`, `decision` ("allow"/"deny") | permission 応答 |

### 4.4 フィールド命名規約

- snake_case (`session_id`, `chat_id`, `tool_name`, `request_id`, `seq`)
- 真偽値は `is_*` プレフィックス無し (フィールド名から自明)
- timestamps は epoch ms (`ts`, `created_at_ms`)
- enum は string literal (`"allow"`, `"listening"`, `"idle"` 等)

### 4.5 atomicity 保証 (NFR-13, AD-03)

#### 4.5.1 送信側ルール (Phone)

- `current_state` は単一の `combine` flow で導出され、変更があれば**全フィールド (mode + 全 payload) を含む 1 wire イベント**で送る
- `current_state` 送信時に `seq` を単調増加 (起動時 0)
- 単独変更でよいフィールドは別イベント (`input_text_only`) として出して良いが、**「mode 不変条件を破らないフィールド」だけ** 許可される
- **サブイベントは `parent_seq` を持つ** (= 送信時点の最新 `current_state.seq`)。受信側はこの値で「この更新がどの state を前提とするか」を判断する
- 不変条件 (Phase 2 で固定):
  - mode=PERMISSION_CONFIRMING のとき `pending_permission != null`
  - mode=LISTENING のとき `transcript_state == LISTENING`
  - mode=CONFIRMING のとき `transcript_text != "" or input_text != ""`
  - mode=IDLE のとき制約なし
  - `mic_source = PHONE_FALLBACK` は `transcript_state` が IDLE 以外のときのみ意味を持つ (IDLE 時は値を見ない)
- これに違反するフィールドを単独で変えてはならない。例えば `pending_permission` の単独更新はサブイベント禁止 → 必ず `current_state` 経由
- Phone UI 側も同じ `combine` flow を購読する → UI も 1 描画フレーム内で乖離しない

#### 4.5.2 受信側ルール (Glass)

- Glass は `lastSeenStateSeq: Int = 0` を持つ
- Glass は `pendingInputText: Pair<Int, String>?` (= 未来の current_state を待つ input_text バッファ、Phase 3 で具体実装) を持つ
- **CXR session_open 受信時に `lastSeenStateSeq = 0`、`pendingInputText = null` にリセット** (Phone プロセス再起動シナリオ対応、B-1 解消)
- `current_state` 受信時:
  1. `event.seq <= lastSeenStateSeq` ならドロップ (stale)
  2. それ以外は `PhoneState` data class を**新規 immutable インスタンスとして組み立て、_phoneState.value に 1 アトミックに swap**
  3. `lastSeenStateSeq = event.seq`
  4. `pendingInputText` が `event.seq` に対応するもの (`pendingInputText.first == event.seq`) であれば、その input_text を apply して `pendingInputText = null`
- `input_text_only` 受信時:
  1. `event.parent_seq < lastSeenStateSeq` ならドロップ (古い state に対する更新)
  2. `event.parent_seq > lastSeenStateSeq` なら `pendingInputText = (event.parent_seq, event.input_text)` として**保留** (current_state 到着を待つ。B-5 解消)
  3. `event.parent_seq == lastSeenStateSeq` のときだけ、現在の `PhoneState` を `.copy(inputText = event.input_text)` で更新して swap
- 個別フィールドを直接 mutate する API は提供しない (= "atomic に組み立てて置換" のみ)
- **巻き戻し race の防止**: `current_state` と `input_text_only` で **独立した判定** を行うことで、サブイベントが state 更新を上書きしたり、古い state 更新がサブイベントを無効化したりするケースを排除する
- **保留バッファの上限**: 同時に保留できるのは最新の 1 件のみ。新しい `input_text_only` が来たら古いものを破棄

#### 4.5.3 永続化との関係 / 再起動時の同期

`current_state` を永続化しない (§3.3) ため、Phone app 再起動後は `seq` も 0 から再開する。

| 再起動シナリオ | Glass `lastSeenStateSeq` リセット契機 | 回復経路 |
|---|---|---|
| **Glass プロセス再起動** (Phone 生存) | CXR session_open 受信時に Glass 側で 0 リセット | Glass が `hello` 送出 → Phone が refresh → 新 `current_state(seq=1+)` 受信 |
| **Phone プロセス再起動** (Glass 生存) | CXR session_open 受信時に Glass 側で 0 リセット (B-1 解消) | CXR 再接続成立 → Glass が `hello` 送出 → Phone が refresh → 新 `current_state(seq=1+)` 受信 |
| **双方再起動** | 同上 | 同上 |

**重要**: §4.5.2 のリセットルールにより、Phone と Glass のどちらが先に再起動しても、CXR 再接続のタイミングで Glass 側 `lastSeenStateSeq` が必ず 0 に戻る → seq=1 の current_state も stale ドロップされない。

### 4.6 ID とライフサイクル

| ID | 発行者 | 形式 | 寿命 | 失効条件 |
|---|---|---|---|---|
| `session_id` | Claude Code (`--session-id`) | UUID v4 | Claude session 寿命 | `/clear` で新規発行 (= 旧 session_id は dead) |
| `chat_id` | Hub | UUID v4 | `POST /send` 1 回 | reply 受信で完了 (タイムアウトは設けない) |
| `request_id` | Claude (Bridge 経由) | UUID v4 (Claude 由来) | permission 要求 1 件 | verdict 送信 または `permission_abort` で完了 |

**冪等性**:
- 同 `chat_id` の reply 重複 → Phone 側 UI は dedup (FR-PH-35)
- 同 `request_id` の permission 重複 → Phone UI / Hub / Bridge 全て dedup (FR-PH-46, FR-HU-10, FR-BR-08)
- `permission_abort` 受信後の verdict 送信 → Hub が `410 Gone` を返す

#### 4.6.1 permission_abort vs verdict のレース

| シナリオ (時系列) | Hub の振る舞い | Phone UI の振る舞い | Glass の振る舞い |
|---|---|---|---|
| (A) ユーザ verdict → その後 abort | verdict 通過、その後の abort は Phone へは push しない (UI 上完了済み) | 通常完了 | 通常完了 |
| (B) abort → その後ユーザ verdict | Phone が `permission_abort` を先に受け、UI から消える → ボタンが存在しない (発火しない) | 「Claude 側で取消されました」snackbar | UI から自動消去 |
| (C) abort と verdict が同時 (Hub 側で同 tick) | verdict が後着なら `410 Gone` で拒否、`permission_abort` を Phone に push | 「Claude 側で取消されました」snackbar (verdict が拒否されたことの表示) | `permission_abort` で UI から消去 |

**実装ガイドライン (Phase 3 詳細)**:
- Hub は request_id ごとに `verdict_sent` flag を持ち、abort 先勝ちの場合は flag セット → 後着 verdict を 410 Gone
- Phone Repository は `permission_abort` 受信時に該当 pending を `pendingPermissions` から消す
- Phone は verdict POST の戻り値が 410 だったときに snackbar 表示

### 4.7 SSE 接続成立直後の reconciliation (起動時 / 再接続時共通)

新規 / 再接続の SSE オープン後、**Hub は必ず以下を順に push する** (Rev 5 更新):

1. `session_snapshot` (現在 Hub 側でアクティブな session_id 一覧) — FR-HU-05
2. **`permission_snapshot { request_ids }`** (現時点で outstanding な request_id を createdAtMs 昇順で含む) — FR-HU-14 + AD-13 (Phase 3)
3. **outstanding な `permission` イベントを createdAtMs 昇順で全て個別 push** — FR-HU-14
   - verdict 未送出 / abort 未着のもののみ
   - Phone 側は `permission_snapshot` で local pending を絞り込んだ上で、個別 push を冪等 (FR-PH-46) に処理
4. (以降) リアルタイムイベント `session_active` / `session_inactive` / `reply` / `permission` / `permission_abort` / etc.

Phone 側ルール:
- `session_snapshot` 受信時に、自分が持っている current_session が snapshot に含まれていなければ FR-PH-54 ルールで補正 (詳細 §6.5)
- outstanding `permission` 再 push は新規 permission と同じパスで処理される (冪等)
- 以降のリアルタイム push は冪等規則で merge

---

## 5. データモデル (shared types)

`:protocol` module で定義し、Phone / Glass の両 app から参照する。Hub / Bridge (TS) は同等の型を手書き。下記は**論理型を示すための例** (実装ファイルではない)。

### 5.1 主要型

```kotlin
sealed interface WireEvent {
    val ts: Long

    // Phone↔Glass
    data class CurrentState(
        val seq: Int,
        val mode: ConversationMode,
        val pendingPermission: PendingPermission?,
        val transcriptState: TranscriptState,
        val transcriptText: String,
        val inputText: String,
        val micSource: MicSource,
        override val ts: Long,
    ) : WireEvent

    data class InputTextOnly(val parentSeq: Int, val inputText: String, override val ts: Long) : WireEvent
    data class SessionList(val sessions: List<SessionSummary>, override val ts: Long) : WireEvent
    data class CurrentSession(val id: String?, override val ts: Long) : WireEvent
    data class Messages(val sessionId: String?, val messages: List<ChatMessage>, override val ts: Long) : WireEvent
    data class Notification(val kind: NotificationKind, val text: String, val sessionId: String?, override val ts: Long) : WireEvent
    data class Error(val message: String, override val ts: Long) : WireEvent

    data class Hello(override val ts: Long) : WireEvent
    data class SelectSession(val id: String, override val ts: Long) : WireEvent
    data class Gesture(val which: GestureKind, override val ts: Long) : WireEvent
    data class ListeningCancel(override val ts: Long) : WireEvent
    data class PermissionVerdict(val requestId: String, val decision: PermissionDecision, override val ts: Long) : WireEvent

    // session lifecycle
    data class SessionOpen(override val ts: Long) : WireEvent
    data class SessionClose(override val ts: Long) : WireEvent
    data class Ping(override val ts: Long) : WireEvent
}

enum class ConversationMode { IDLE, LISTENING, CONFIRMING, PERMISSION_CONFIRMING }
enum class TranscriptState { IDLE, CONNECTING, LISTENING, ERROR }
enum class MicSource { GLASS, PHONE_FALLBACK }
enum class NotificationKind { REPLY, PERMISSION }
enum class GestureKind { TAP, DOUBLE_TAP, SWIPE_FORWARD, SWIPE_BACK }
enum class PermissionDecision { ALLOW, DENY }
enum class MessageRole { OUTGOING, INCOMING, SYSTEM }

data class PendingPermission(
    val requestId: String,
    val toolName: String,
    val description: String,
    val inputPreview: String,
    val sessionId: String?,
    val createdAtMs: Long,
)

data class SessionSummary(
    val id: String,
    val label: String,
    val messageCount: Int,
)

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val chatId: String?,
)
```

備考: 上記は **論理型**。具体的なシリアライゼーション annotation (`@SerialName` 等) は Phase 3 で確定。

### 5.2 Phone↔Hub の HTTP/JSON 型 / Hub↔Bridge の NDJSON 型

§4.3.1 / §4.3.2 の表で示した shape をそのまま型化。Phone 側のみで参照される型 (Hub↔Bridge 型は TS のみ) は Phase 3 で実装。

---

## 6. 主要シーケンス

### 6.1 S1: Phone から送信 → reply 受信 (Phone のみ)

```
User           Phone UI       ChannelRepo    Hub          Bridge      Claude
 |               |              |              |             |           |
 |--type text--->|              |              |             |           |
 |--tap send---->|              |              |             |           |
 |               |--send(text)->|              |             |           |
 |               |              |--POST /send->|             |           |
 |               |              |              |--send IPC--->|         |
 |               |              |              |             |--channel msg-->
 |               |              |<--chat_id----|             |           |
 |               |<--update UI--|              |             |           |
 |               |              |              |             |<--reply tool--
 |               |              |              |<--reply IPC--|           |
 |               |              |<--SSE reply--|              |           |
 |               |<--append msg-|              |              |           |
 |<--show reply--|              |              |              |           |
```

### 6.2 S2: Glass 経由音声入力 → 確認 → 送信

```
User    Glass UI    GlassBridge   Phone GCS     Phone Repo   TranscriptClient   OpenAI
 |        |           |             |             |             |                |
 |--Tap-->|           |             |             |             |                |
 |        |--gesture(tap) wire----->|             |             |                |
 |        |           |             |--repo.toggleTranscription |                |
 |        |           |             |             |--start mic + ws------------->|
 |        |           |             |             |             |--audio frames->|
 |        |           |             |             |             |<--partial deltas
 |        |           |             |<--state update (LISTENING, transcript)----|
 |        |<--current_state(seq=N)--|             |             |                |
 |        |--show partial           |             |             |                |
 |--Tap (stop)------->|             |             |             |                |
 |        |--gesture(tap) wire----->|             |             |                |
 |        |           |             |--toggle (stop) + confirming=true           |
 |        |           |             |<--current_state(seq=N+1, CONFIRMING)------|
 |        |<--show "送信/取消"-------|             |             |                |
 |--SwipeForward----->|             |             |             |                |
 |        |--gesture(swipe_forward)>|             |             |                |
 |        |           |             |--sendCurrent + confirming=false            |
 |        |           |             |             |--POST /send  (S1 と同じ)    |
```

### 6.3 S3: permission 要求 → Phone と Glass 両方で UI 表示

```
Claude --> Bridge --> Hub --> Phone (SSE: permission)
                                |
                                +-- Phone Repo: pendingPermissions に追加
                                +-- mode 判定 (IDLE → 別 session なら switch / 同 session ならそのまま)
                                +-- current_state derive (seq=N+1): PERMISSION_CONFIRMING + pending payload
                                |
                                +-- Phone UI: PermissionDialog (現 session のみ)
                                +-- Glass push: current_state (mode + pending を atomic)
                                |
                User --[Allow/Deny]--> Phone UI or 通知シェード or Glass
                                |
                                +-- POST /permission(request_id, behavior)
                                +-- Hub: verdict_sent flag を立てる → Bridge --> Claude
                                +-- pendingPermissions から削除 → mode が IDLE に戻る
                                +-- current_state derive (seq=N+2, mode + pending=null を atomic)
```

**重要点**: `current_state` を 1 wire + seq で送るため、Glass 側で「mode は PERMISSION_CONFIRMING だが pending が null」という瞬間が存在しえない (NFR-13)。

### 6.4 切断と再接続

```
Phone↔Hub: SSE 切断検出 → ConnectionController が exp backoff (§7.4) で再接続
                       → 接続成立後、Hub から session_snapshot を受信 → §6.5 reconciliation
Phone↔Glass: CXR-L 切断 → GlassConnectionService の Status が DISCONNECTED → 再接続待機
            → Glass プロセス再起動時は Glass から hello → Phone (GlassRelay) が refresh
            → 全 StateFlow が再エミット (current_state は次の seq で送る)
```

### 6.5 起動時 / 再接続時 reconciliation (FR-PH-54 対応)

```
Phone app launch / SSE 再接続
├── 履歴 + last current_session を _state に復元 (永続化から)
├── ChannelService FGS 起動 (AppLifecycleController.startChannel)
└── SSE 接続成立
    ├── Hub --> session_snapshot { active_session_ids: [...] }
    │   Phone 側:
    │     a. current_session が active_session_ids に含まれる → そのまま継続
    │     b. 含まれない && active_session_ids 非空 → 最新の active session に切替 (Should: FR-PH-54)
    │     c. 含まれない && active_session_ids 空 → current_session = null (一覧画面相当)
    └── 以降のリアルタイム push (session_active / session_inactive / reply / permission) を受信
```

### 6.6 UNKNOWN_SESSION_ID → 確定 session_id へのマージ (FR-PH-55)

```
1. Phone: POST /send (session_id=null, text="...")
   → outgoing message を session_id=UNKNOWN_SESSION_ID で保存
   → current_session = UNKNOWN_SESSION_ID
2. Hub: chat_id 発行 → Bridge 経由で Claude へ
3. Claude: reply 発火 (この時点で Claude 内で session_id が確定)
4. Bridge → Hub → Phone: SSE reply { chat_id, session_id, text }
5. Phone Repo (マージ処理):
   a. chat_id を持つ outgoing message を UNKNOWN_SESSION_ID から session_id に移動
   b. 受信 reply は session_id で保存
   c. current_session が UNKNOWN_SESSION_ID だったら session_id に切替
   d. messagesBySession[UNKNOWN_SESSION_ID] が空になったら session list から消す
```

### 6.7 アプリ kill 状態での verdict 経路 (FR-PH-48, NFR-14)

**プロセス境界の前提**: Phone app は **単一プロセス** (`android:process` を分けない)。理由: DataStore / OkHttpClient / Repository singleton をプロセス間で共有する複雑さの方が、kill 時の独立 Service 経路を作るコストより遥かに大きい。

**ユーザが Activity をスワイプ kill すると、ChannelService FGS も含めて全プロセスが死ぬ** (近年の Android では FGS だけが生存する保証は無い)。したがって verdict 経路は「冷起動可能な軽量 path」で設計する:

```
Phone (Activity kill, 全プロセス死)
├── 通知シェードには permission 通知が残っている
│   (FGS が事前に notify 済み、通知は OS が保持)
├── ユーザが Allow タップ
├── PendingIntent (Intent extras に request_id, behavior, baseUrl, token を全て埋め込み済み)
├── 軽量 Service `VerdictDispatchService` が起動 (cold start)
│   ├── Application.onCreate (DataStore 等を最小限初期化)
│   ├── extras から request_id + behavior を取り出す
│   ├── DataStore から baseUrl + token を読む (フォールバック: extras に入れた値)
│   ├── OkHttpClient new() → POST /permission(request_id, behavior)
│   └── 完了で stopSelf()
└── Verdict が Hub に届く (NFR-14: 5s 以内)
```

**NFR-14 (5s) の根拠**:
- BroadcastReceiver の 10s ANR 制約は通らない経路 (Service 起動経由)
- 冷起動: Application.onCreate ≤ 500ms (DataStore 初期化を最小限に保つ)
- DataStore 読み出し: ≤ 100ms
- DNS + TCP + POST: ≤ 2000ms (LAN/Tailscale 前提、NFR-01 と同水準)
- 合計 ≤ 3s。NFR-14 (5s) は余裕で達成

**PendingIntent extras に token を埋める判断**:
- アプリ kill 直後でも DataStore は generally 読める (同一 UID の StorageManager 経由) が、保険として PendingIntent 生成時の token を extras に冗長化
- セキュリティ: extras はアプリ内 PendingIntent なので他アプリから読めない (`FLAG_IMMUTABLE` 必須)

**実装上の注意 (Phase 3 詳細)**:
- `VerdictDispatchService` は `Service` (FGS でない) で OK。3s 以内に stopSelf すれば BroadcastReceiver 10s 制約より十分早い
- 通常時 (アプリ前景 / FGS 生存時) は Repository 経由の通常 path を使う。`VerdictDispatchService` は kill 時のみ起動

#### 6.7.1 Token rotate 後 (HTTP 401) の挙動

token がローテートされた状態で kill 中通知から Allow を押したケース。UI が無いため snackbar 表示できないが、ユーザに通知文言で気付かせる:

```
VerdictDispatchService が POST /permission → 401 を受信
├── 通知を消さずに残し続ける (cancel しない)
├── 通知文言を書き換え:
│   タイトル: 「再ペアが必要」
│   本文: 「Hub の token が変わりました。アプリを開いて QR を再スキャンしてください」
│   action ボタン: なし (もう Allow/Deny を押させない)
├── PendingIntent をアプリ起動に張り直す (タップで MainActivity を開いて設定ダイアログ表示)
└── log に warn 出力
```

これで NFR-14 の「verdict が 5s 以内に到達」は 401 時には**満たさない** (満たせない) が、ユーザは通知文言から再ペアに気付ける。AC-01 の前提として「token rotate と kill 状態 verdict が重ならないこと」を Phase 5 テスト時の前提条件に明示する。

### 6.8 BT SCO 確保失敗 → 内蔵マイクへフォールバック (FR-GL-44)

```
Glass Tap → Phone Repo.startTranscriptionFromGlass
├── AudioRouter.routeToGlassMic()
│   ├── (試行) AudioManager.setCommunicationDevice(SCO/LE)
│   ├── 成功 → routed=true, mic_source=GLASS
│   └── 失敗 → routed=false, mic_source=PHONE_FALLBACK
├── TranscriptionClient.start()
│   └── AudioRecord は VOICE_RECOGNITION で起動 (BT 通っていれば BT mic、失敗時は内蔵)
└── current_state を更新:
    - transcript_state = LISTENING
    - mic_source フィールドで GLASS / PHONE_FALLBACK を区別 (§4.3.3 で定義)
    - Glass HUD は mic_source = PHONE_FALLBACK のとき「内蔵マイク使用中」を表示
```

### 6.9 IP 変更 (LAN ↔ Tailscale 切替等) の吸収 (FR-PH-09)

```
Phone の network 状態変化 (WiFi 切替 / Tailscale 接続 / アンメッシュ等)
├── 既存の SSE 接続が物理的に切断される (TCP 接続 dead)
├── ConnectionController が onFailure を検出 → exp backoff (§7.4) で reconnect ループ
│   - 設定された baseUrl で再接続を試みる
│   - 新しい NIC で route が成立すれば成功
└── 成功で §6.5 reconciliation を実行
```

**設計判断**: 能動的な IP 変更検知 (ConnectivityManager で listening) は v1.0 では行わない。**再接続ループに吸収させる** (= シンプル、Tailscale のような透過的な routing 変化にも自然に対応)。能動検知が必要になるユースケース (例: 即時切替を期待) があれば Phase 3 以降で追加。

---

## 7. 横断的設計判断 (ADR)

### 7.1 AD-03: mode + payload atomicity (NFR-13 / P1-4 解消)

**問題**: `current_mode` と `pending_permission` のようなフィールドを別 wire で push する素朴な構造では、Glass / Phone 双方で 1 描画フレーム内に状態が乖離する hazard がある。

**決定**:
1. 単一の `current_state` wire イベントを定義し、mode 決定に関わる全 payload を 1 つにまとめて送る
2. `current_state` に `seq: Int` (単調増加) を含め、受信側は **stale ドロップ** + **immutable swap** で更新
3. 例外的に高頻度更新が必要なフィールドのみ別サブイベント (`input_text_only`) を許可。ただし**サブイベントは mode 不変条件 (§4.5.1) を破らないフィールドのみ** に限定
4. Phone UI 側も同じ `combine` flow を購読する → UI も 1 描画フレーム内で乖離しない

**代替案と却下理由**:
- (a) 順序保証 (payload 先, mode 後 の送信順) — 通信層のジッタや受信側の処理順序で破綻
- (b) Glass 側 debounce / hold-back — 症状を隠すだけ、別 wire を追加するたびに考慮事項が増える
- (c) 細粒度更新 (mode のみ wire、payload は別 StateFlow 共有) — 同一プロセスでないので不可
- (d) `current_state` を 1 wire + seq + サブイベント許可 (= 採用) — bundle のシンプルさを保ちつつ、入力中の高頻度更新が性能を圧迫するのを避ける

**影響**:
- Glass 側 `dispatch` は `current_state` イベント 1 つで主要状態を全更新 (個別 setter は提供しない)
- 個別 wire (`current_mode` / `pending_permission` / `transcript_state` / `input_text`) は提供しない
- AC-09 (mode race) は §7.7 の自動検証手段で機械的に判定可能

### 7.2 AD-01: wire protocol の物理表現

**決定**: Phone↔Glass は **Caps バイナリ** (Rokid CXR-L SDK が要求するため)。Phone↔Hub は **JSON over HTTP/SSE**。Hub↔Bridge は **JSON NDJSON over loopback**。全て §5.1 の論理型から派生する。

**代替案と却下理由**:
- protobuf 統一: solo dev / 単一プラットフォーム規模では toolchain コストが過大
- Caps 統一 (Phone↔Hub も Caps): TypeScript 側に Caps codec を実装する負担が過大、デバッグ性も悪い

**影響**: `:protocol` module は `CapsCodec` と `JsonCodec` を両方提供する。論理型と物理表現の分離が明確化。

### 7.3 AD-02: shared module の物理形態

**決定**: **Kotlin library module `:protocol`** を作成し、Phone app + Glass app の両方から Gradle 依存として参照する。Hub / Bridge (TS) は本文書 §4.3 を参照して TS 型を手書きし、CI に **双方向 parity test** を置く (Kotlin↔TS の encode/decode を golden file 比較)。

**代替案と却下理由**:
- Kotlin Multiplatform: Hub/Bridge は TS なので KMP の Node target は使えない (kotlin-node あるが experimental + 学習コスト過大)
- CodeGen (YAML schema → Kotlin/TS): 真にエレガントだが solo dev v1.0 の規模では over-engineering

**影響**:
- Phone, Glass の Kotlin 側は機械的に drift しない (= NFR-50 Must の一部達成)
- Hub, Bridge の TS は手動同期。CI parity test で drift を実行時に発見できる

**parity test の実装 (Phase 5 で詳細)**:
- Kotlin 側 golden: 全 `WireEvent` を JSON 文字列化して `golden/kotlin/<event>.json` に保存
- TS 側 golden: 同等を `golden/ts/<event>.json` に保存
- Kotlin test: `golden/ts/*.json` を Kotlin decode → 元の論理型と equal assert
- TS test: `golden/kotlin/*.json` を TS decode → 元の論理型と equal assert
- 両方 green でないと CI fail

### 7.4 AD-04: 再接続戦略

**決定**: 指数バックオフ。初期 1 秒、係数 2、上限 30 秒、ジッタ ±25%。成功で 1 秒にリセット。Hub↔Phone (SSE) と Phone↔Glass (CXR-L) で共通の戦略。Glass↔Phone は再接続成立後に Glass から `hello` wire を送ることで Phone 側の全 StateFlow 再エミットを誘発する。

**根拠**:
- NFR-10 (再接続成功率 100%, 復旧中央値 < 30s) を満たす最小限の戦略
- 30 秒上限は Hub の再起動 / WiFi 切替の最悪ケースをカバーしつつ、復旧中央値 < 30s と整合
- ジッタは個人ツール (single client) では理論上不要だが、Hub 起動中の競合を回避する保険

### 7.5 AD-05: FGS オーケストレーション

**問題**: 素朴な構造では `GlassConnectionService.start/stop` が内部で `MicForegroundService.start/stop` を呼ぶような FGS 同士の結合が生まれやすい。さらに「複数 FGS の状態遷移の一貫性」を保つ責務を誰も持っていない場合、連打 / 自然切断 / shutdown が走った時の挙動が暗黙になる。

**決定**:
1. `AppLifecycleController` (Phone app object) を導入し、FGS の起動/停止は必ずこの controller を経由 (§3.2.1)
2. controller は明示的な **state machine** で Glass+Mic FGS の状態を管理 (§3.2.2)
3. 全 API は冪等
4. Glass 自然切断は `GlassConnectionService` → `controller.onGlassDisconnected()` で通知
5. controller の状態は `StateFlow<GlassFgsState>` で UI から監視可能

**Phase 3 で確定**: 内部の状態保持実装、stop 完了待機の具体機構、AndroidX Lifecycle との結合方式。

### 7.6 履歴削除ポリシー

**決定**: 書込時に LRU 退避を行う。

- 1 session の合計サイズが 20 MB を超えた場合: その session 内の古いメッセージから削除 (画像優先)
- 全 session の合計サイズが 200 MB を超えた場合: `updated_at` の古い session を丸ごと削除

**根拠**: 起動時掃除は Phone の起動を遅らせる、cron 的な定期実行は FGS の責務が増える。書込時 trigger は ChannelRepository の永続化フローと自然に同居する。

### 7.7 テスト戦略 (粒度)

| 層 | 粒度 | カバレッジ目標 (v1.0) | 実行頻度 |
|---|---|---|---|
| 純関数 (codec / decoder / state mapper) | Unit | **80%+** | コミット毎 (CI) |
| State machine (`ConversationStateHolder` 等) | Unit + flow テスト | **70%+** | コミット毎 (CI) |
| Wire parity (Kotlin ↔ TS, 双方向) | golden file based | 全イベント被覆 | CI |
| Repository / Service 結合 | mock 化 unit | 主要 path のみ | コミット毎 (CI) |
| UI (Compose) | Espresso / Robolectric は省略 | — | 実機での AC テスト |
| End-to-end (実機 + 実 Hub + 実 Claude) | 手動 | 主要シナリオ (S1, S2, S3) | リリース前 |

#### 7.7.1 NFR-13 (atomicity) の自動検証手段

人間の目で 16ms 描画フレームを判定するのは事実上不可能。AC-09 を機械的に判定するため:

1. Glass app は **`glass_state_swap`** イベント (§7.14 の必須フィールド全部入り) を、`PhoneState` を swap した直後に毎回 emit
2. Phone app は **`phone_state_emit`** イベント (同じく必須フィールド全部) を combine flow から emit する直前に毎回 emit
3. テストランナーは log を post-mortem で読み、各イベントが §4.5.1 の不変条件を満たすか検証:
   - `mode=PERMISSION_CONFIRMING` のとき `pending_request_id != null`
   - `mode=LISTENING` のとき `transcript_state == LISTENING`
   - `mode=CONFIRMING` のとき `transcript_state != IDLE` または `input_len > 0`
   - `mode=IDLE` のとき制約なし
4. 全 `glass_state_swap` / `phone_state_emit` で違反が 0 件なら AC-09 合格

実装は Phase 5 (テストランナー設計時) に確定。出力例:
```
2026-05-16T14:23:45.123Z DEBUG glass_state_swap seq=42 mode=PERMISSION_CONFIRMING pending_request_id=req-abc-123 transcript_state=IDLE input_len=0 mic_source=GLASS
```
この行を 1 行ずつ parse して assert すれば良い。

### 7.8 AD-06: エラーモデル (Rev 2 新規)

**問題**: `String` ベースの素朴なエラー (`sendError = e.message ?: e.toString()`) や `runCatching + Log.w` による暗黙握りつぶしを許すと、Phase 1 で要求された「明示的エラー」(FR-PH-13/14/16/22, FR-GL-44) を満たす実装根拠が無くなる。

**決定**:
1. **`sealed class WireError`** を `:protocol` module または各 app の data 層で定義
2. 主要ケース (Phase 2 段階で確定):

```
WireError
├── Connection
│   ├── NotConfigured              (settings が空)
│   ├── ConnectFailed(cause)       (TCP / DNS 失敗等)
│   ├── AuthFailed                 (X-Token 不正、HTTP 401)
│   └── ServerError(httpCode, body)
├── Send
│   ├── ImageTooLarge(actualBytes, limitBytes)
│   ├── SessionNotActive(sessionId)
│   └── Cancelled                  (POST 中にユーザがアプリ kill)
├── Permission
│   ├── Aborted(requestId)         (verdict 送信前に abort された)
│   ├── AlreadyVerdicted           (重複送信 / 410 Gone)
│   └── Unknown(requestId)
├── Transcription
│   ├── ApiKeyMissing
│   ├── ApiKeyInvalid
│   ├── MicPermissionDenied
│   ├── NetworkFailed
│   └── ServiceError(message)
└── Glass
    ├── TokenMissing
    ├── HiRokidNotInstalled
    ├── CxrConnectFailed
    └── BtScoUnavailable           (内蔵マイクへフォールバックしたことを示す)
```

3. **層責務**:
   - **data 層 (Repository / Client)**: 例外を catch → `WireError` に分類してエミット (`StateFlow<WireError?>` or `SharedFlow<WireError>`)
   - **UI 層**: `WireError` を受信して **表現** (snackbar / dialog / banner) にマッピング
4. **UI 表現マッピング** (詳細は Phase 3):
   - 一時的 (送信失敗等): **snackbar**
   - ユーザ確認が要る (画像サイズ超過等): **dialog**
   - 永続的状態 (TokenMissing 等): **banner** (画面上部)

**代替案と却下理由**:
- 例外 throw 継続: ハンドル漏れの温床、Compose の宣言的 UI と相性悪い
- 単一 `error: String?`: 表現マッピングを UI 各所で書くことになり DRY 違反

### 7.9 AD-07: Token 寿命 / rotation / revocation (Rev 2 新規)

**問題**: NFR-20/21 が token のエントロピーだけ書いていて、寿命管理 / 失効 / 再交付フローが未設計。

**決定**:

**X-Token (Phone↔Hub)**:
- Hub 起動時に永続値を `.env` の `CLAUDE_CHANNEL_TOKEN` に保存。Hub プロセスが alive な間はこの値で認証
- 再生成 CLI: `claude-mobile-hud rotate-token` — 既存 `.env` を上書き + Hub を再起動 (旧 token は瞬時に死ぬ)
- **Phone 側の失効検知トリガ**: `ChannelClient` が HTTP **401 Unauthorized** を受けたら `WireError.Connection.AuthFailed` を Repository に emit → UI 層が**自動で設定ダイアログを表示**しつつ snackbar で「token が無効です。QR を再スキャンしてください」を出す
- SSE 接続も同様: SSE establish 時の HTTP 401 → reconnect ループを止め、AuthFailed 状態を保持 (再ペアまで再試行しない)

**Glass CXR-L token**:
- Hi Rokid 経由で取得し、Phone の EncryptedSharedPreferences に保存
- Phone UI に「認可を解除」ボタン — タップで Phone 側 storage と GlassConnectionService を停止
- 再認可は Hi Rokid フロー再実行

**OpenAI API Key**:
- ユーザが UI で投入、DataStore (平文) 保存
- 失効はユーザが UI で削除して再入力

**端末紛失時の運用** (ドキュメント上の手順):
- Phone 紛失 → PC で `claude-mobile-hud rotate-token` → 旧 Phone は接続失敗で実質失効
- Glass 紛失 → Hi Rokid (Rokid アカウント側) で revocation (本プロジェクト範囲外)

**Phase 3 で確定**: Phone 側で「token 期限切れ → 自動でダイアログ表示」のトリガ実装、CLI コマンドの詳細仕様。

### 7.10 AD-08: 多重起動戦略 (Rev 2 新規)

**Hub** (FR-HU-09):
- 起動時に listening port (`8788` for Phone, `8787` for Bridge) を bind 試行
- bind 失敗 = 既に Hub が起動中 → stderr に明示エラー、exit code 非ゼロで終了

**Phone** (FR-PH-85, FR-PH-33):
- `MainActivity` の launchMode = `singleTask` (`taskAffinity=""` は外す — singleTask の挙動に冗長 / 反するため)
- 既存 task があればそれを前景に持ち上げ、新 Intent は `onNewIntent` で受け取る
- 通知タップ経路 (FR-PH-33):
  - `NotificationFactory.reply / permission` で作る `PendingIntent` に Intent extras を載せる: `session_id`, optional `notification_kind`
  - `MainActivity.onNewIntent` で extras を読み、`ChatViewModel.selectSession(sessionId)` を呼んで該当 session の会話画面に切替
- FGS (ChannelService 等) は **冪等** に設計 (startForeground を複数回呼んでも害がない既存 API 挙動)

**根拠**:
- Hub のポート bind 失敗は OS レベルで自然に競合検知できる (追加の lockfile 不要)
- `singleTask` のみで「別 task 経由起動でも 1 つに集約 + 既存 task 前景化」が成立。`taskAffinity=""` の意義は薄く、誤動作の温床になる
- `onNewIntent` で session_id を受け取る経路を明示することで、Phase 4 で「通知から開いても前回画面のまま」というバグを防ぐ

### 7.11 AD-09: 画像処理仕様 (FR-PH-64, Rev 2 新規)

**決定** (Phone UI 入力時の Phone 側で完結):
- 最大入力サイズ: 端末 ContentResolver が読めるもの全て (上限なし)
- ダウンスケール: max edge = **1280 px**
- 再圧縮: JPEG quality 80
- **EXIF 全除去** (位置情報含む、回転情報を除く — 回転はピクセル上で適用)
- 出力: `base64 + mime` ペア (`image/jpeg` 固定)
- 履歴保存サイズ: 圧縮後の base64 文字列をそのまま JSON に埋め込む

**Phase 3 で確定**: 具体的な Bitmap 処理コード、エラー (壊れた画像 / 巨大画像のメモリ溢れ) ハンドリング詳細。

### 7.12 AD-10: PendingPermission キュー順序 (FR-PH-44, Rev 2 新規)

**決定**: Phone 内部の `pendingPermissions: List<PendingPermission>` を以下でソートして UI に表示:

1. 第 1 キー: `if (sessionId == currentSessionId) 0 else 1` (現 session 優先)
2. 第 2 キー: `createdAtMs` 昇順 (FIFO)

**UI 表現**:
- 現 session の先頭 1 件 → `PermissionDialog` (FR-PH-44)
- 他 session の件数 → タイトルに「+他 N 件」表示
- ドロワー (FR-PH-44 should) で全 pending を session 別にグルーピング表示 (Phase 3 詳細)

**根拠**: 現 session 優先 = ユーザが見ているコンテキストを尊重。FIFO = 古い要求から処理 (タイムアウト前に拾える可能性)。

**Cleanup / Capacity 規則** (Rev 3 追加):

| トリガ | 振る舞い |
|---|---|
| `session_inactive` 受信 (= Bridge が die) | 当該 session の pending を UI から消す。**verdict は Hub に送らない**。Hub が FR-HU-13 により当該 outstanding 全てに対し `permission_abort` を自動送出するため、Phone は abort 受信で更に冪等 cleanup される |
| ユーザがセッション削除 (FR-PH-52) | 該当 session に pending が残っていれば「未応答の permission が N 件あります。一括で許可 / 拒否しますか?」確認ダイアログ → 一括 verdict 送信後に削除実行 |
| `AppLifecycleController.shutdownAll` | UI は閉じるが、pending は揮発する (Hub 側で abort されるまで Hub に残る) |
| キュー上限 (32 件) を超過 | 古い順に強制 deny の verdict を送信 + log に記録。UI には警告 snackbar |

**根拠**:
- session_inactive で勝手に verdict を送ると、Claude 側 Bridge は既に die しているので意味がない + 別 session への誤適用リスク
- 上限 32 は個人ツール規模の上限。32 件 pending が貯まる状況は異常 (Hub 障害 / Phone 長時間オフライン) なので強制 deny で safe-fail
- shutdown 時の揮発は意図的。Hub 側 abort が来るまでユーザ視界外で pending が残るが、再起動後 SSE reconnect で `permission` イベントが再 push される (Hub が保持している場合)

### 7.13 AD-11: i18n / 文字列リソース化 (Rev 2 新規)

**決定**:
- v1.0 サポート言語: **日本語のみ**
- ただしハードコードしない: 全ユーザ向け文字列を `strings.xml` 経由で参照
- 英語版追加は `values-en/strings.xml` を追加するだけで機械的に実現可能
- Compose 内では `stringResource(R.string.xxx)` を徹底
- ログ / 内部識別子 (event 名等) は英語固定 (リソース化不要)

**根拠**: 個人ツールで v1.0 は日本語固定で十分だが、ハードコード方式 (`"接続中…"` のリテラル直書き) は将来の言語追加でコストが膨らむ。**最初からリソース化規約**を設けるだけで実装コストはほぼ無い。

### 7.14 AD-12: 観測性 / 相関 ID 伝播 (Rev 2 新規)

**問題**: `Log.d/i/w` の自由フォーマットでは、複数経路を流れる 1 つの操作 (例: Phone POST /send → Hub → Bridge → Claude reply tool → Phone SSE reply) を後で追跡する手段が無い。

**決定**:
1. **構造化ログ形式** (key=value 型) を全レイヤで採用:
   ```
   <ISO8601> <level> <tag> event=<name> chat_id=<X> session_id=<Y> request_id=<Z> ...
   ```
2. **相関 ID の透過伝播**:
   - `chat_id` は Hub mint → SSE reply / send 経路で必ず付与
   - `request_id` は Claude mint → permission / verdict / abort 経路で必ず付与
   - `session_id` は確定後 (UNKNOWN_SESSION_ID 期間以外) で必ず付与
3. **重要イベント** (Phase 2 で固定):

| event | 必須フィールド | 出力タイミング |
|---|---|---|
| `wire_send` | `chat_id?, request_id?, session_id?, wire_event_name` | wire 送信直前 |
| `wire_recv` | `chat_id?, request_id?, session_id?, wire_event_name` | wire 受信直後 |
| `permission_lifecycle` | `request_id, phase` (= `received` / `verdict_sent` / `aborted`) | permission の状態変化 |
| `fgs_state` | `fgs_kind` (= `channel` / `glass` / `mic`), `transition_from`, `transition_to` | AppLifecycleController state machine 遷移 |
| `transcription_lifecycle` | `phase` (= `start` / `stop` / `partial` / `error`), `mic_source` | transcription の状態変化 |
| `error` | `error_class, error_message, chat_id?, request_id?, session_id?` | `WireError` emit |
| **`glass_state_swap`** | `seq, mode, pending_request_id\|null, transcript_state, input_len, mic_source` | **Glass 側で `PhoneState` を新規 swap した直後**。AC-09 機械判定の根拠 |
| **`phone_state_emit`** | `seq, mode, pending_request_id\|null, transcript_state, input_len, mic_source` | **Phone 側で current_state を combine flow から emit する直前**。Phone UI atomicity 判定の根拠 |

`glass_state_swap` / `phone_state_emit` の必須フィールドは **§4.5.1 の不変条件を全て機械判定可能にする粒度** で設計されている。AC-09 の自動検証 (§7.7.1) は両イベントを grep + assert で検証する。

4. **Telemetry / crash reporting**: v1.0 では実装しない (個人ツール)

**Phase 3 で確定**: logcat のタグ規約、ログレベルガイドライン、Hub/Bridge 側の log 出力方式 (stdout / 構造化 JSON ライン)。

---

## 8. Phase 1 §11 への決着 (要約)

§1.2, §1.3 でも触れたが、ここで完全表として整理。

| Phase 1 項目 | 確定値 / 決定 | 本文書での位置 |
|---|---|---|
| §11.1 NFR-40a 電池 (未接続) | < 10% (実機計測で見直し可) | §1.2 |
| §11.1 NFR-40b 電池 (接続中) | < 25% (実機計測で見直し可) | §1.2 |
| §11.1 NFR-41 履歴上限 | 20 MB/session, 200 MB 全体, LRU | §1.2, §7.6 |
| §11.1 マルチセッション上限 | ハード制限なし (200 MB ソフトキャップで自然抑制) | §1.2 |
| §11.1 Glass 接続未確立しきい値 | 15 秒 | §1.2 |
| §11.2 wire 物理表現 | Caps + JSON (論理型から派生) | §4.1, AD-01 |
| §11.2 shared module 形態 | Kotlin library + TS 手書き + 双方向 parity test | §4.2, AD-02 |
| §11.2 mode + payload bundling | `current_state` 1 wire + seq + immutable swap (AD-03 強化) | §4.5, AD-03 |
| §11.2 再接続 backoff | 1s→30s exp + ±25% jitter | §7.4, AD-04 |
| §11.2 ID 生成 | UUID v4。session_id=Claude, chat_id=Hub, request_id=Claude | §4.6 |
| §11.2 履歴削除トリガー | 書込時 LRU | §7.6 |
| §11.2 テスト粒度 | 表で定義 + NFR-13 自動検証 (§7.7.1) | §7.7 |
| §11.2 FGS オーケストレータ | `AppLifecycleController` + state machine | §3.2, AD-05 |
| 〈Rev 2 追加〉エラーモデル | sealed WireError + 層責務 | §7.8, AD-06 |
| 〈Rev 2 追加〉Token 寿命 | X-Token: rotate-token CLI / Glass: Hi Rokid 任せ / **Phone 側は HTTP 401 検出で自動再ペアダイアログ** | §2.4, §7.9, AD-07 |
| 〈Rev 2 追加〉多重起動 | Hub: port bind 失敗 / Phone: singleTask + onNewIntent (FR-PH-33 経路) | §7.10, AD-08 |
| 〈Rev 2 追加〉画像処理 | 1280px max / JPEG 80 / EXIF 除去 | §7.11, AD-09 |
| 〈Rev 2 追加〉PendingPermission キュー | (現 session 優先, FIFO) + **session_inactive / 削除 / 上限 32 件 の cleanup 規則** | §7.12, AD-10 |
| 〈Rev 2 追加〉i18n | 日本語固定 + strings.xml 経由 | §7.13, AD-11 |
| 〈Rev 2 追加〉観測性 | 構造化ログ + 相関 ID 透過伝播 + **`glass_state_swap` / `phone_state_emit` 専用イベントで AC-09 機械判定可能** | §7.14, AD-12 |

---

## 9. Phase 3 (詳細設計) への引き継ぎ

Phase 2 で interface / 構造 / 横断判断を定めた。Phase 3 では以下を確定する:

### 9.1 内部設計

- 各コンポーネントのクラス図 / モジュール構成
- StateFlow / SharedFlow の具体的な combine ロジック (NFR-13 を達成する Phone 側 flow 構成)
- `AppLifecycleController` の内部実装 (state machine の Kotlin 実装、AndroidX Lifecycle との統合)
- `:protocol` module の codec 実装詳細 (`@SerialName` 規約)
- Hub / Bridge の internal structure (TS module 構成)
- 履歴ファイルフォーマット (JSON か sqlite か。AD-09 の base64 サイズ感を踏まえて判断)
- `WireError` の具体的な sealed class 階層 (§7.8 のリストを実装)
- `AppLifecycleController` の StateFlow 実装

### 9.2 重要シーケンスの実装詳細

- §6.5〜§6.9 の各シーケンスを Kotlin / TS のコードフローに落とす
- BroadcastReceiver から POST までの具体的な実装 (§6.7)
- BT SCO フォールバック時の UI 表現 (§6.8)

### 9.3 横断テーマ

- ログ / メトリクス: logcat タグ規約、Hub/Bridge stdout 形式 (§7.14)
- strings.xml の実際の文字列群 (§7.13)
- Phone 側 token rotation 検知 UI フロー (§7.9)
- テストランナー (NFR-13 自動検証, §7.7.1)
- crash reporting は v1.0 では実装しない (確認済み)

### 9.4 Phase 2 4 巡目レビューで挙がった Phase 3 持ち越し論点

4 巡目の独立レビューで「Phase 3 で自然に扱える」と判定された新規論点。Phase 3 着手時に検討する。

| 優先度 | 項目 | 内容 | 想定する解 |
|---|---|---|---|
| 高 | **Hub プロセス再起動で outstanding state がロスト** | FR-HU-12 は in-memory 保持を要求するが Hub 再起動で消える。Bridge は再接続できても `request_id ↔ bridge` map と outstanding 状態が失われ、Phone から pending が永久に消えない可能性 | Phase 3 冒頭の AD で「Hub 再起動時、in-flight permission は全 abort 扱いとし `permission_abort` を Phone へ push」を決定 (FR-HU 拡張も合わせて検討) |
| 中 | Bridge `register` 確定前の race | Bridge が register 送信前に reply / permission を流すケースの挙動が未定義 | Phase 3 で「register 前のイベントは reject (UNKNOWN_SESSION_ID 経路へ振替) または queue」を決める |
| 中 | 通知 channel id 変更時の kill 中通知挙動 | アプリアップグレードで channel id を変える場合、旧 channel に乗った PendingIntent extras を新コードがパースできない可能性 | Phase 3 で「v1.0 期間中は channel id 固定」を明記、もしくは extras schema に versioning |
| 低 | outstanding 再 push の順序保証 | FR-HU-14 / §4.7 は順序未指定。Phone は §7.12 で createdAtMs 昇順で並べたいので、Hub も同順序で push しないと一瞬の表示逆転が起きる | FR-HU-14 に「createdAtMs 昇順」を加筆 (Phase 3 内で Phase 1 微修正 or Phase 3 設計判断として吸収) |
