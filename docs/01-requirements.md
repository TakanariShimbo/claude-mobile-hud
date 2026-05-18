# Phase 1: 要件定義

`claude-mobile-hud` が何を提供するかを定める。**何を作るかと作らないか** を明確にし、設計判断 (= Phase 2 以降) の判断材料にする。

- **作成日**: 2026-05-16
- **改訂**: Rev 5 (Phase 3 詳細設計で AD-13 を確定したことに伴う FR-HU-14 文言修正 + FR-HU-15 追加)
- **対象**: `claude-mobile-hud` v1.0
- **記述方針**:
  - "should be true" (振る舞い / 状態) で書く。"how to achieve" は Phase 2 以降で扱う
  - 要件には ID を振る (例: `FR-PH-01`)。後工程からトレースする
  - 優先度は MoSCoW: **Must** (必須) / **Should** (望ましい) / **Could** (あれば嬉しい) / **Won't** (今回はやらない)

---

## 1. 目的と背景

### 1.1 目的

PC で動く Claude Code セッションを、**ハンズフリー / 離れた場所から監視 / 操作するためのモバイル + ウェアラブルクライアント** を提供する。

### 1.2 背景

- Claude Code は通常、PC のターミナル上で対話する。長時間タスク (調査・ビルド・デプロイ) を任せたいとき、ターミナルに張り付かないと進捗が見えず、ツール承認も机に戻らないと押せない
- 「phone + Rokid Glasses で Claude Code を遠隔操作する」体験には有用性があると確認されている
- 本プロジェクトは、構造的負債 (wire protocol 二重定義 / state race / 責務混在 など) を避けるため、**要件・基本設計・詳細設計を先に固めた上で実装する waterfall 流れ** で構築する

### 1.3 主役

このプロジェクトの**主役は phone app と glass app**。 hub / bridge は phone を支えるための PC 側インフラとして必要最小限の責任を持つ。

---

## 2. 用語集

| 用語 | 意味 |
|---|---|
| **Claude Code** | Anthropic 公式の対話型 CLI (`claude` コマンド) |
| **Channel** | Claude Code research preview の MCP 拡張機能。`<channel ...>` メッセージ受信と MCP tool 経由の reply 送出を可能にする |
| **Session** | Claude Code の 1 つの会話単位 |
| **session_id** | Session の識別子。`/clear` で新規発行され、`--resume` で温存される。1 つの Bridge プロセスに 1 つ対応 |
| **UNKNOWN_SESSION_ID** | `session_id` が確定する前の reply / permission を一時的に紐付けるセンチネル値 |
| **chat_id** | 1 回の phone → Claude 送信と、その応答 (reply) を対応付けるためのキー。**Hub が発行** (dedup 責任) |
| **request_id** | 1 つの permission 要求 (ツール実行承認) を対応付けるキー。**Claude (Bridge 経由) が発行** |
| **Phone** | Android 上のメインクライアントアプリ |
| **Glass** | Rokid Glasses (HUD ウェアラブル) 上のコンパニオンアプリ |
| **Hub** | PC で常駐するデーモン。phone と通信し Bridge を中継する |
| **Bridge** | Claude Code 各セッションに 1 つ紐付く MCP サーバ。Claude が自動で起動する |
| **CXR-L** | Rokid のクロスデバイス通信プロトコル。phone ↔ glass 間で使う |
| **Hi Rokid** | Rokid の認可ブローカーアプリ。CXR-L の token 発行と接続中継を担う |
| **HUD** | Head-Up Display。Rokid Glasses の透過表示部分 |
| **FGS** | Foreground Service (Android)。バックグラウンドでも継続実行するための仕組み |

### 2.1 表記規約

- wire protocol (JSON / Caps) 上のフィールドは **snake_case**: `chat_id`, `session_id`, `request_id`
- 本文中での参照も同じ snake_case を使う
- 固有名詞 (Phone / Glass / Hub / Bridge / Claude) は文頭以外でも **先頭大文字**
- コード識別子 (chatId など camelCase) は実装の詳細であり要件書では使わない

---

## 3. ユーザ像と利用シナリオ

### 3.1 想定ユーザ

- Claude Code を日常的に使う**個人開発者** (本人含む)
- PC は Linux (Ubuntu 想定)。Android 端末を所持
- Rokid Glasses は所有していてもいなくても良い (glass は optional)
- 自宅 LAN または Tailscale で PC と Phone が同一ネットワーク上にある

### 3.2 主要シナリオ

#### S1. PC から離れて作業を進めたい

1. PC で Claude Code を起動し長時間タスクを投げた
2. ユーザは別作業 (家事 / 移動 / 会議など) のために PC を離れる
3. Phone を見ると、Claude からの reply が通知に届いている
4. 通知をタップすると Phone アプリの該当 session の会話画面が開く
5. 必要なら Phone から続きの指示を送る
6. ツール承認が必要なときは Phone に通知が届き、通知シェードから直接 / アプリ起動して許可・拒否できる

##### S1.1 サブシナリオ: Hub 不達のまま送信操作した

- ユーザがホームに着く前に PC のスリープで Hub が落ちている
- Phone から送信を試みると **明示的なエラー** (再試行可) が UI に出る
- 後で Hub が復活した時、自動再接続が成立する

##### S1.2 サブシナリオ: アプリ kill 状態で permission 通知が来た

- ユーザは Phone アプリをスワイプで kill しているが、ChannelService FGS は別プロセスで生きている
- 通知シェードに permission 通知が出る
- 通知から直接 Allow / Deny を押せて、verdict は Hub に届く (アプリ起動は必須ではない)

#### S2. ハンズフリーで監視したい

1. ユーザは Rokid Glasses を装着して別作業をしている
2. Claude が reply / permission 要求を出すと、Glass HUD に通知が流れる
3. ジェスチャで会話画面に入り、最新 reply を視界の隅で確認
4. 音声入力 (Glass マイク) で次の指示を入れる、または permission 承認をジェスチャで返す

##### S2.1 サブシナリオ: Hi Rokid 未認可で Glass 接続を試みた

- ユーザは初めて Glass 接続を試みる
- Phone は Hi Rokid アプリがインストールされていない / 認可されていないことを検知し、案内を出す
- ユーザは Hi Rokid を起動して認可を完了 → Glass 接続が継続する

#### S3. 複数セッションを同時に走らせる

1. ユーザは複数の Claude Code セッションを並行起動 (調査タスク + リファクタタスクなど)
2. Phone のセッション一覧でアクティブなものを見渡せる
3. 任意のセッションに切り替えて履歴と現状を確認
4. それぞれのセッションに個別に指示を送れる

---

## 4. スコープ

### 4.1 対象コンポーネント

| コンポーネント | 役割 | 主役か |
|---|---|---|
| Phone app | 入力ハブ / 履歴持ち / permission 応答 / セッション管理 | ★ 主役 |
| Glass app | HUD 表示 + ジェスチャ + 音声入力 | ★ 主役 |
| Hub | PC 側常駐デーモン。Phone との通信中継 | 必要最小限 |
| Bridge | Claude セッションごとの MCP サーバ | 必要最小限 |

### 4.2 含めるもの

- 上記 4 つのコンポーネント実装
- Phone ↔ Hub ↔ Bridge ↔ Claude 間の wire protocol 仕様
- Phone ↔ Glass 間の wire protocol 仕様 (CXR-L Caps)
- セットアップ手順 (Hub 起動 / pair / Claude 起動)

### 4.3 含めないもの

詳細は §8 (スコープ外)。

---

## 5. 機能要件

### 5.1 Phone app

#### 接続 / 設定

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-01 | Hub の baseUrl と X-Token を保存できる | Must |
| FR-PH-02 | QR コード (PC 側生成) を読み取って baseUrl + token を取り込める | Must |
| FR-PH-03 | 設定を手動で編集できる (QR が使えない環境のフォールバック) | Must |
| FR-PH-04 | 起動時に保存済み設定で自動接続する | Must |
| FR-PH-05 | 接続状態 (idle / connecting / open / failed) を UI に表示する | Must |
| FR-PH-06 | 切断時は自動再接続する (戦略は Phase 2、SLO は NFR-10) | Must |
| FR-PH-07 | 手動の再接続トリガーを提供する | Should |
| FR-PH-08 | QR payload にバージョン番号を含め、未知バージョンは明示的なエラーで弾く | Must |
| FR-PH-09 | LAN / Tailscale 切替や IP 変更を再接続ループで吸収する (ユーザ操作不要) | Must |

#### メッセージ送信

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-10 | テキストメッセージを送信できる | Must |
| FR-PH-11 | 画像 (1 メッセージ 1 枚) を添付して送信できる | Must |
| FR-PH-12 | 送信先セッションを選択できる (= 現在 session を変更できる) | Must |
| FR-PH-13 | 送信中 / 送信完了 / 送信失敗を UI に表示する | Must |
| FR-PH-14 | 送信失敗時に同じ内容で再試行できる (入力は失われない) | Must |
| FR-PH-15 | 入力は session ごとに保持する (session を切替えても下書きが残る) | Should |
| FR-PH-16 | Hub 不達時の送信は失敗エラーで返す (端末側でキューイングしない) | Must |

#### 音声入力

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-20 | OpenAI Realtime API を使った音声入力ができる (API key 設定時のみ) | Should |
| FR-PH-21 | 録音中の partial / 確定 transcript を入力欄に反映する | Should |
| FR-PH-22 | 音声入力エラー (API キー無効 / ネットワーク失敗 / 権限拒否) を UI に明示する | Must |
| FR-PH-23 | アプリがバックグラウンドでも音声入力を継続できる (Glass 接続時のみ要求) | Must |

#### Reply 受信

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-30 | Claude からの reply を受信して履歴に追加する | Must |
| FR-PH-31 | アプリ前景時は会話画面に直接表示する | Must |
| FR-PH-32 | アプリ背景時はシステム通知を出す | Must |
| FR-PH-33 | 通知タップでアプリを開き、該当 session の会話画面を表示する | Must |
| FR-PH-34 | 別 session の reply は、現在の作業を中断しない (session 切替を起こさず modal / banner を被せない) | Must |
| FR-PH-35 | 同一 chat_id の reply が重複到着した場合、UI 上は一度だけ表示する (冪等) | Must |

#### Permission (ツール承認)

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-40 | Permission 要求を受信して UI に表示する | Must |
| FR-PH-41 | Allow / Deny の応答を送信できる | Must |
| FR-PH-42 | 通知シェードからも Allow / Deny できる (バックグラウンド時) | Must |
| FR-PH-43 | Permission 要求の内容 (toolName / description / input preview / requestId) を確認できる | Must |
| FR-PH-44 | 複数 pending がある場合、現在 session のものを最上位に表示し、他 session の件数をバッジ等で示す | Should |
| FR-PH-45 | 別 session の permission は、現在の作業を中断しない (session 切替を起こさず modal を被せない) | Must |
| FR-PH-46 | 同一 request_id の重複到着は冪等 (一度しか UI に出さない、verdict は一度しか送らない) | Must |
| FR-PH-47 | Claude 側で abort された pending を Hub から通知された場合、UI から消す | Must |
| FR-PH-48 | アプリ kill 状態でも通知シェードの Allow / Deny が機能する (= FGS 経由で verdict が Hub に届く) | Must |

#### セッション管理

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-50 | アクティブなセッションを一覧表示する (アクティブ = Hub から `session_active` を受信、かつ `session_inactive` を未受信) | Must |
| FR-PH-51 | セッションを切替えられる | Must |
| FR-PH-52 | セッションを削除 (= 端末側履歴クリア) できる | Must |
| FR-PH-53 | 削除は PC 側 Claude セッションには影響しないことが UI で明示される | Must |
| FR-PH-54 | アプリ起動時、直前のアクティブセッションを復元する | Should |
| FR-PH-55 | UNKNOWN_SESSION_ID に紐付いたメッセージは session_id 確定後にマージできる (詳細手段は Phase 2) | Should |

#### 履歴

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-60 | 送受信メッセージを端末ローカルに永続化する | Must |
| FR-PH-61 | 画像も履歴の一部として保存する (再表示できる) | Must |
| FR-PH-62 | アプリ再起動後に履歴を復元する | Must |
| FR-PH-63 | 履歴の保持上限 (件数 / 合計サイズ) を持ち、超過時は古い session から削除するポリシーを持つ。上限値は NFR-41 で定義 | Must |
| FR-PH-64 | 添付画像は入力時にダウンスケール + 再圧縮し、EXIF (位置情報含む) を除去してから保存する | Must |

#### Glass 連携

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-70 | Hi Rokid 認可フローを起動して CXR-L token を取得できる | Must |
| FR-PH-71 | CXR-L 経由で Glass app に接続できる | Must |
| FR-PH-72 | Glass 接続中はバックグラウンドでも通信を維持する | Must |
| FR-PH-73 | Glass からのジェスチャ / セッション選択 / permission 応答を受信して処理する | Must |
| FR-PH-74 | アプリ状態 (session / message / mode / transcript / input / pending permission) を Glass に push する。同時更新時の atomicity は NFR-13 |  Must |
| FR-PH-75 | Hi Rokid アプリが未インストール / 未認可の場合、Glass 接続を試みず案内を出す (シナリオ S2.1) | Must |
| FR-PH-76 | Glass が切断していた間に発生した reply / permission の扱いは「再接続時に最新状態を再 push、過去通知のリプレイはしない」 | Must |

#### 権限 / アプリライフサイクル

| ID | 要件 | 優先度 |
|---|---|---|
| FR-PH-80 | バックグラウンドでも reply / permission を受信できる | Must |
| FR-PH-81 | 完全終了 (明示的なユーザ操作) で全 FGS を停止する | Must |
| FR-PH-82 | 終了は誤タップ防止のため確認ダイアログを挟む | Should |
| FR-PH-83 | RECORD_AUDIO 権限拒否時は音声入力ボタンを非活性にし、設定への導線を出す (アプリ全体は動作する) | Must |
| FR-PH-84 | POST_NOTIFICATIONS 権限拒否時は通知に依存しない degraded mode で動作する (前景時の UI と FGS は機能する) | Must |
| FR-PH-85 | 二重起動を防ぐ (既に起動中の場合は前景に復帰させる) | Should |

### 5.2 Glass app

#### 接続

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-01 | Phone と CXR-L で接続する | Must |
| FR-GL-02 | 接続状態を画面に表示する | Must |
| FR-GL-03 | 切断時は Phone 側に状態を伝え、再接続を待つ | Must |
| FR-GL-04 | 起動時、Phone 側の現在状態を再取得する (= hello / re-sync) | Must |
| FR-GL-05 | Phone 接続が長時間確立しない場合、ユーザに状態と次の操作 (Phone アプリの確認) を提示する (しきい値は §11.1 で確定) | Should |

#### 入力 (ジェスチャ / 物理リモコン)

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-10 | Tap / DoubleTap / SwipeForward / SwipeBack の 4 種類を受け付ける | Must |
| FR-GL-11 | Rokid 物理リモコンのキー (ENTER / DPAD / BACK) を上記ジェスチャにマッピングする | Must |
| FR-GL-12 | アクション意味は画面モードによって変わる (例: Listening 中の Tap は録音停止) | Must |
| FR-GL-13 | 各画面で受け付ける gesture と意味を画面下部 1 行に常時表示する | Must |
| FR-GL-14 | Phone から再 push された state を反映する (=未確定の Confirming 状態などを保持) | Must |

#### セッション選択画面

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-20 | アクティブなセッション一覧を表示する | Must |
| FR-GL-21 | カーソル移動 (Swipe) → 決定 (Tap) でセッションに入れる | Must |
| FR-GL-22 | アクティブセッションが 0 のときは明示する | Must |

#### 会話画面

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-30 | 現在 session の履歴を表示する | Must |
| FR-GL-31 | 最新の reply (= Incoming の最新メッセージ) は色 / フォントウェイトで他履歴と区別して強調する | Must |
| FR-GL-32 | 履歴をスクロールできる | Must |
| FR-GL-33 | 入力中テキスト / 録音中 partial を画面下に表示する | Must |

#### 音声入力

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-40 | Tap で音声入力を開始/停止できる | Must |
| FR-GL-41 | 音声は Glass マイクから Bluetooth 経由で Phone に渡る | Must |
| FR-GL-42 | 録音状態 (idle / connecting / listening / error) を画面に表示する | Must |
| FR-GL-43 | 録音停止後、確定 transcript を含む送信確認モード (Confirming) に入る | Must |
| FR-GL-44 | Bluetooth SCO / LE Audio Headset の確保に失敗した場合、Phone 内蔵マイクへフォールバックして音声入力を継続する。フォールバックされたことを UI で明示する | Must |

#### 送信確認モード (Confirming)

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-50 | 「送信」「取消」を Swipe で選択、Tap で決定する | Must |
| FR-GL-51 | DoubleTap で取消 (= 送らずに会話画面に戻る) | Must |
| FR-GL-52 | session を跨いで作業中の確認状態を保持する (別 session に移っても戻ってきたら継続) | Should |

#### Permission 承認

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-60 | 現在 session に対する permission 要求を画面に表示する | Must |
| FR-GL-61 | 「許可」「拒否」を Swipe で選択、Tap で決定する | Must |
| FR-GL-62 | DoubleTap は安全側 (= 拒否) として扱う | Should |
| FR-GL-63 | 別 session の permission は表示せず、現在 session に切り替わるまで待つ | Must |

#### 通知

| ID | 要件 | 優先度 |
|---|---|---|
| FR-GL-70 | reply 通知時に画面 OFF からの wake と通知音 (chime) を鳴らす | Must |
| FR-GL-71 | permission 通知は reply と区別できる音 (例: 2 連) で鳴らす | Should |
| FR-GL-72 | 会話画面が見えている間は画面を OFF にしない | Must |

### 5.3 Hub

| ID | 要件 | 優先度 |
|---|---|---|
| FR-HU-01 | Phone からの HTTP/SSE 接続を受け付ける | Must |
| FR-HU-02 | X-Token による認証を行う | Must |
| FR-HU-03 | Bridge との IPC を中継する (Phone → Claude / Claude → Phone) | Must |
| FR-HU-04 | request_id → 発行元 Bridge をルーティングして permission verdict を返す | Must |
| FR-HU-05 | アクティブな session の一覧を Phone に push する | Must |
| FR-HU-06 | 単一ユーザ / 単一 PC 前提で動作する | Must (multi-user は OOS-01) |
| FR-HU-07 | Phone 向け HTTP リスニングポートと Bridge 向け IPC ポートは設定可能。デフォルト値は Phase 2 で確定 | Must |
| FR-HU-08 | Bridge との IPC は loopback (`127.0.0.1`) のみで受け付け、外部公開しない | Must |
| FR-HU-09 | 二重起動を検出し、後発プロセスは明示的なエラーで終了する | Must |
| FR-HU-10 | chat_id を発行し、同一 chat_id の重複登録を冪等に扱う (= dedup の責任を持つ) | Must |
| FR-HU-11 | Bridge から abort 通知を受けた permission を Phone に伝搬する | Must |
| FR-HU-12 | outstanding permission (= verdict 未送出 / abort 未着の request_id) を内部状態として保持する。各 request_id について発行元 Bridge / 内容 / 受信時刻を覚える | Must |
| FR-HU-13 | Bridge プロセス終了 (= IPC 切断) を検知した場合、その Bridge に紐付く outstanding permission 全てに対し `permission_abort` を Phone へ自動 push する。Bridge 由来の明示的 abort 通知 (FR-HU-11) と区別不可な形で送出して良い | Must |
| FR-HU-14 | Phone との SSE 接続成立直後、以下の順序で push する: (1) `session_snapshot`, (2) `permission_snapshot { request_ids }` (現時点で outstanding な request_id を **createdAtMs 昇順**で含む), (3) 続いて個別 `permission` イベントを createdAtMs 昇順で送出。Phone 側は冪等処理 (FR-PH-46) と `permission_snapshot` による local pending 絞り込みで整合させる | Must |
| FR-HU-15 | Hub プロセス再起動直後の outstanding state は空集合として扱う (永続化しない)。Bridge 再接続による outstanding 復旧は Bridge / Claude 側の挙動に依存 | Must |

### 5.4 Bridge

| ID | 要件 | 優先度 |
|---|---|---|
| FR-BR-01 | Claude Code から MCP stdio で起動される | Must |
| FR-BR-02 | `reply` MCP tool を提供する (Claude → Phone への返信用) | Must |
| FR-BR-03 | Claude の `<channel>` 経由メッセージを Hub に転送する | Must |
| FR-BR-04 | Claude の permission 要求を Hub 経由で Phone に届け、verdict を Claude に返す | Must |
| FR-BR-05 | Claude セッションと同じプロセス寿命を持つ (Claude が落ちたら die) | Must |
| FR-BR-06 | 添付画像を Claude が読み込める形で渡す (具体方式は Phase 2) | Must |
| FR-BR-07 | Claude 側で permission が abort された場合、Hub にその通知を流す | Must |
| FR-BR-08 | 同一 request_id が重複到着しても Claude に渡すのは一度のみ (冪等) | Must |

---

## 6. 非機能要件

### 6.1 性能

| ID | 要件 | 目標値 | 計測方法 (概要) | 優先度 |
|---|---|---|---|---|
| NFR-01 | テキスト送信 → Hub 受信のレイテンシ | LAN 内 中央値 < 200ms / p99 < 500ms | Phone 側送信時刻と Hub 受信時刻を比較 (時刻同期は NTP 前提) | Must |
| NFR-02 | Claude reply → Phone 受信のレイテンシ | Bridge → Phone 経路で 中央値 < 500ms (Claude 生成時間は含まない) | Bridge 発火時刻と Phone 受信時刻を比較 | Must |
| NFR-03 | 音声 partial transcript の表示遅延 | OpenAI API 応答受信から UI 反映まで < 200ms | API 応答時刻と Compose 描画時刻 | Should |
| NFR-04 | Phone ↔ Glass のジェスチャ反映 round trip | < 300ms (gesture 発火 → 画面更新) | Glass 側計測 | Must |

### 6.2 信頼性 / 復旧性

| ID | 要件 | 目標値 / 詳細 | 優先度 |
|---|---|---|---|
| NFR-10 | 切断時の自動再接続が成立する | 8h 以上の連続テスト中、人為的に発生させた切断 (≥5 回) に対し再接続成功率 100%。復旧時間中央値 < 30s | Must |
| NFR-11 | Glass プロセス再起動からの状態回復 | 再起動後 5s 以内に Phone から状態を再 push し、UI が最新状態に追従 | Must |
| NFR-12 | アプリ kill / 再起動後の履歴復元 | 履歴 JSON が破損していない限り、メッセージ件数と内容が完全に復元される | Must |
| NFR-13 | mode と payload の atomicity (ちらつき防止) | ユーザに観測される **Phone UI / Glass UI 双方の 1 描画フレーム** において、`current_mode` の値とそれに対応する payload (pending_permission / transcript_state / input_text) が乖離した状態にならない | Must |
| NFR-14 | 通知シェード経由 Allow/Deny のアプリ kill 時動作 | アプリ kill 状態でも 5 秒以内に verdict が Hub に到達する | Must |

### 6.3 セキュリティ

| ID | 要件 | 目標値 / 詳細 | 優先度 |
|---|---|---|---|
| NFR-20 | Hub ↔ Phone は token 認証 | token は ≥128 bit のエントロピーを持つランダム値 | Must |
| NFR-21 | Glass CXR-L token は端末内で暗号化して保存する | OS が提供する KeyStore 系仕組みを使う (具体実装は Phase 2) | Must |
| NFR-22 | OpenAI API key は端末ローカルに保存し、ログ / 通知 / 履歴に出力しない | grep / logcat で漏洩が無いことを確認 | Must |
| NFR-23 | Hub ↔ Bridge は loopback のみで通信する | 外部 NIC では LISTEN しない | Must |
| NFR-24 | TLS (HTTPS) | — | Won't (LAN / Tailscale 前提) |
| NFR-25 | End-to-end 暗号化 | — | Won't |

### 6.4 互換性 / 環境

| ID | 要件 | 優先度 |
|---|---|---|
| NFR-30 | Android 12+ (minSdk 31) で動作 | Must |
| NFR-31 | Android 14+ の FGS-microphone bg-launch 制約に対応 | Must |
| NFR-32 | Rokid Glasses + Hi Rokid アプリの最新版で動作 | Must |
| NFR-33 | Claude Code CLI v2.1.140+ で動作 | Must |
| NFR-34 | claude.ai Pro / Max / Team / Enterprise login が前提 | Must |
| NFR-35 | iOS 対応 | Won't |
| NFR-36 | macOS / Windows PC 対応 | Won't (Linux only) |

### 6.5 電池 / リソース

| ID | 要件 | 目標値 | 計測条件 | 優先度 |
|---|---|---|---|---|
| NFR-40a | Glass 未接続時の電池消費 | 8h 待機で **< 10%** | ChannelService FGS のみ常駐、Phone は画面 OFF、Hub 接続維持 | Must (実機計測で値見直し可) |
| NFR-40b | Glass 接続時の電池消費 | 8h 待機で **< 25%** | GlassConnection + Mic FGS 常駐、Bluetooth 接続維持、録音は行わない | Must (実機計測で値見直し可) |
| NFR-41 | 履歴 1 session あたりの保存サイズ上限 | 画像込みで < **20 MB** (超過時は古い session から削除) | Must |
| NFR-42 | CXR-L 接続が無いときは Glass 関連 FGS を立てない | — | Must |

### 6.6 保守性

| ID | 要件 | 優先度 |
|---|---|---|
| NFR-50 | wire protocol の定義が単一の真実源から参照され、両端 (Phone / Glass / Hub / Bridge) の drift が **コンパイル時または CI で機械的に検出可能** | Must |
| NFR-51 | wire decode / state machine / 純関数は副作用と分離されており unit test が書ける | Should |
| NFR-52 | 各 FGS の責務境界 (ChannelService = Hub 接続維持, GlassConnectionService = CXR-L 接続, MicForegroundService = マイク常駐) が単一責務で、相互に他 FGS の起動 / 停止を直接呼ばない | Must |
| NFR-53 | 設計判断 (なぜそうしたか) をコード or docs に記録する | Should |

---

## 7. 制約

| ID | 制約 | 影響 |
|---|---|---|
| CON-01 | Claude Channels は research preview。`--dangerously-load-development-channels` などのフラグやイベント名が Claude Code 各バージョンで変わりうる | Bridge の Channel 連携部分は変更追従が必要 |
| CON-02 | claude.ai Pro/Max/Team/Enterprise login が必須 (API key 単体不可) | セットアップ手順に明示 |
| CON-03 | Rokid CXR-L SDK (Caps バイナリ) を使用 | submodule で取り込む既存実装の継続 |
| CON-04 | Hi Rokid アプリを経由しないと CXR-L token が取れない | Phone に Hi Rokid インストールが必須 |
| CON-05 | Bridge の session-id 探索は `/proc` 経由 (Linux only) | PC は Linux 限定 |
| CON-06 | OpenAI Realtime API は別途課金 | API key 設定は optional 機能 |
| CON-07 | LAN または Tailscale 等で Phone と PC が同一ネットワークにある必要がある | リモート利用 (公衆網) は未対応 |

---

## 8. スコープ外 (Non-goals)

明示的に **やらないこと**。後から「実は必要」が判明したら別フェーズで再評価する。

| ID | 項目 | 理由 |
|---|---|---|
| OOS-01 | マルチユーザ / マルチ PC 対応 | 個人ツールとして設計 |
| OOS-02 | クラウド同期 (履歴 / 設定) | Phone がローカルで source of truth |
| OOS-03 | iOS クライアント | 主目的 (Glass 連携 = Android only) と整合しない |
| OOS-04 | macOS / Windows PC 対応 | Bridge の `/proc` 依存 |
| OOS-05 | TLS / mTLS / E2E 暗号化 | LAN / Tailscale 前提の脅威モデル |
| OOS-06 | Web UI / デスクトップ UI | Phone + Glass の使い分けで足りる |
| OOS-07 | プラグインシステム / 拡張機構 | 個人ツールの規模では不要 |
| OOS-08 | 画像以外の添付 (PDF / 動画 / 任意ファイル) および複数添付 | 1 メッセージ 1 画像で十分 |
| OOS-09 | reply 本文の TTS 読み上げ | 視覚 (Glass HUD) で代替できる。注: 通知 chime (SFX) は対象内 (FR-GL-70) |
| OOS-10 | session の編集機能 (履歴改変 / コミット圧縮) | Claude Code 側の `--resume` で十分 |
| OOS-11 | オフライン時の送信キューイング | FR-PH-16 の明示的エラー対応で十分 |

---

## 9. 前提条件

ユーザ側で揃っているべきもの。揃っていないと本プロジェクトは動作しない。

- PC: Ubuntu 22.04+
- Node 22+ (Hub / Bridge 実行用)
- Claude Code CLI v2.1.140+ がインストール済み、claude.ai 認証済み
- Android Phone (Android 12+)、Hi Rokid アプリインストール済み (Glass 使う場合)
- Rokid Glasses + Hi Rokid 認可済み (Glass 使う場合)
- OpenAI API key (音声入力使う場合)
- Phone と PC が同一 LAN または Tailscale 上にある

---

## 10. 受け入れ基準

v1.0 を「完成」とみなす条件。各基準は **観測可能な手順 + 合否条件** で書く。詳細テスト計画は Phase 5 (テスト) で詰める。

| ID | 手順 | 合格条件 |
|---|---|---|
| AC-01 (S1 完走) | 1. PC で Hub 起動、Claude Code セッション開始 2. Phone から「現在時刻を返答せよ」と送信 3. Phone をスリープ 4. Claude が `Bash` ツール承認要求を出す 5. Phone 通知シェードから Allow 6. Claude の reply を Phone 通知で受信 7. 通知タップで会話画面の該当 session を開く | 7 ステップ全てが Phone 単体 (Glass 不使用) で完了し、エラーログ無し |
| AC-02 (S2 完走) | 1. Glass 装着して接続 2. PC で長時間タスク開始 3. Glass HUD に通知が来る 4. ジェスチャで会話画面に入る 5. Tap で音声入力開始 → 発話 → 停止 → Confirming → Swipe で「送信」決定 6. Claude が permission 要求 → Glass HUD で許可 | 6 ステップ全てが Phone 画面を見ずに完了し、reply / verdict が Hub に届く |
| AC-03 (S3 複数 session) | 2 つの Claude Code セッションを同時起動し、Phone で交互に切替えながら 各 session に 3 通ずつ送受信 | 履歴が session ごとに正しく保持され、別 session の reply で現在の作業 (会話画面表示) が中断されない |
| AC-04 (再接続) | 24h 連続接続中に ≥10 回 SSE 切断を人為的に発生 (Hub 再起動 / WiFi OFF/ON / 端末スリープ復帰の各パターンを含む) | 全て自動再接続成立。**復旧時間の中央値 < 30s** (NFR-10 と同条件)。受信 reply 数 == Hub 送出数。履歴 JSON が破損しない |
| AC-06 (wire 仕様一致) | Phase 2 の wire protocol 仕様書 / shared module 定義と、Phone / Glass / Hub / Bridge の実装で扱う wire 形式が完全一致 | drift 検出テスト (NFR-50) が CI で green |
| AC-07 (セットアップ再現性) | README 通りに第三者が作業した結果、初回送受信 1 往復が成功 | 手順書に未記載のステップ無しで送受信成立 (口頭サポート無し) |
| AC-08 (電池 SLO) | NFR-40a / NFR-40b の計測条件で実機測定 | 仮置き上限値 (10% / 25%) を満たす。満たさない場合は値を見直して再合意 |
| AC-09 (mode race) | Glass で permission 要求を 10 回連続再現 (Phone UI または通知シェード経由で都度 verdict を返答) | Phone UI / Glass UI のどちらにおいても mode と payload が乖離する 1 フレームも観測されない |

---

## 11. 未決事項

### 11.1 Phase 1 で再確定すべき要件穴

要件として固める必要があるが、まだ確定値が出ていない項目。Phase 2 着手前にここで埋めるか、明示的に Should / Could に格下げする。

- **NFR-40a / 40b の電池上限値**: 仮置き 10% / 25%。実機計測後に確定
- **NFR-41 の履歴サイズ上限**: 仮置き 20 MB/session。実機運用での感覚値で見直す
- **マルチセッション数の上限**: 同時に何 session まで想定するか (NFR の限界として明示するか / 制限を設けないか)
- **Glass 接続未確立時間のしきい値** (FR-GL-05): 何秒で「長時間未接続」と判断するか

### 11.2 Phase 2 で決める設計判断

要件としては固定せず、設計者が選んでよい項目。要件 ID には依存しない。

- wire protocol の物理表現: Caps バイナリ継続 / kotlinx.serialization JSON / protobuf 等
- shared module の物理形態: Gradle module / Kotlin Multiplatform / source-set include
- mode + payload bundling の具体形 (1 wire 化 / 順序保証 / atomic update): NFR-13 を満たす手段を選ぶ
- 自動再接続のバックオフ戦略 (NFR-10 を満たす形ならどの戦略でも可)
- chat_id / request_id / session_id の生成アルゴリズム
- 履歴削除のトリガー (起動時掃除 / 書込時 LRU 等)
- テストの粒度 / カバレッジ目標
- FGS のオーケストレータ層の物理表現 (FGS 間結合を avoid する具体実装)

---

## 12. 改訂履歴

### Rev 5 (2026-05-16): Phase 3 AD-13 確定に伴う FR-HU 微修正

- **FR-HU-14** 文言修正: 「outstanding を全て再 push」→ 「`permission_snapshot` (createdAtMs 昇順) + 個別 `permission` の順で送出」。Phase 3 §1.2 AD-13 (Hub 再起動時の Phone 幽霊 pending 一掃) を要件レベルで明示
- **FR-HU-15** 追加: Hub 再起動時の outstanding は空集合扱い (永続化しない)

### Rev 4 (2026-05-16): Hub 責務の明示

Phase 2 基本設計の 3 巡レビューで判明した構造的責務漏れを Phase 1 に遡及補完:

- **FR-HU-12** 追加: Hub が outstanding permission を内部状態として保持する責務
- **FR-HU-13** 追加: Bridge 切断時、Hub が outstanding に対し `permission_abort` を Phone へ自動送出する責務
- **FR-HU-14** 追加: SSE reconnect 時、Hub が outstanding permission を Phone に再 push する責務

これにより、Phase 2 で Phone 側の cleanup 設計 (§7.12 など) が依拠する Hub 側仮定が要件として正式に固まる。

### Rev 3 (2026-05-16): Rev 2 への再レビュー反映

- §10 AC 全面書き直し (主観動詞排除)
- NFR-13 atomicity を Phone UI / Glass UI 双方の 1 描画フレームに拡張
- NFR-43 削除 (FR-GL-44 と重複)
- FR-BR-06 / AC-09 の設計漏出を修正
- FR-GL-05 と §11.1 の自己矛盾を解消

### Rev 2 (2026-05-16): 独立レビュー反映

- AC を全面観測可能化
- NFR-13 atomicity を §11 から §6 に昇格
- 17 抜け要件を追加 (session_id 寿命 / chat_id 発行者 / FGS 責務境界 / Hi Rokid 未認可 / 履歴上限 / QR バージョン / 多重起動 等)
- §12 トレーサビリティ表新設
- MoSCoW 再分配 (FR-PH-22 / 33 / FR-GL-13 を Must)

### Rev 1 (2026-05-16): 初版

機能要件 / 非機能要件 / スコープ外を初版として起こす。
