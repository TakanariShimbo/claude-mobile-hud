# claude-mobile-hud

[![CI](https://github.com/TakanariShimbo/claude-mobile-hud/actions/workflows/ci.yml/badge.svg)](https://github.com/TakanariShimbo/claude-mobile-hud/actions/workflows/ci.yml)

PC の Claude Code を、**Android phone と Rokid Glass HUD** から操作するモバイルクライアント群。

- **phone** (Android): 入力ハブ。テキスト / 音声 / 画像 / permission 応答 / セッション切替
- **glass** (Rokid HUD companion): 視界の HUD に reply とツール承認を表示、ジェスチャで操作
- **hub** / **bridge** (PC 側): phone との通信中継と Claude Code Channel への橋渡し
- **`:protocol`** (Kotlin library): phone / glass 間の wire 型定義 (Phase 3 §2 / AD-02)

## このリポジトリの位置づけ

`~/claude-channel` の POC を、**ウォーターフォール流れで設計し直して再構築する第 2 世代**。POC で得た知見と痛みを設計書として書き下した上で、再実装する。

### 進捗

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | POC レビュー / 課題抽出 | ✅ 完了 (前リポジトリの `docs/TODO.md`) |
| Phase 1 | 要件定義 | ✅ 完了 (`docs/01-requirements.md` Rev 5) |
| Phase 2 | 基本設計 (アーキテクチャ / wire protocol) | ✅ 完了 (`docs/02-architecture.md` Rev 5) |
| Phase 3 | 詳細設計 | ✅ 完了 (`docs/03-detailed-design.md` Rev 3) |
| Phase 4 | 実装 | ✅ 完了 (2026-05-17、CI green、AC-05 PASS) |
| Phase 5 | テスト | ✅ 完了 (2026-05-17、test infra + AC-09 verifier + NFR 計測ツール + 運用 subcommand) |
| Phase 6 | リリース・移行 | 🚀 開始準備 (Hub TLS / release variant / 実機長時間測定) |

## ディレクトリ構成

```
~/claude-mobile-hud/
├── docs/                       設計書 (01–04)
├── settings.gradle.kts         Gradle root
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml      バージョンカタログ
│   └── wrapper/
├── gradlew, gradlew.bat
├── protocol/                   Kotlin library subproject (:protocol)
├── phone/                      Android Phone app (:phone)
├── glass/                      Android Glass app (:glass)
├── cxrglobal/                  git submodule (CXR-L SDK ラッパー)
├── hub/                        Hub (TypeScript, Node)
├── bridge/                     Bridge (TypeScript, Node)
├── claude-mobile-hud           ディスパッチャ CLI
├── tools/
│   ├── verify_atomicity.py     NFR-13 / AC-09 自動検証ランナー
│   └── test_verify_atomicity.py
└── .github/workflows/ci.yml    GitHub Actions CI (protocol / hub / bridge / phone / glass)
```

## 必要環境

- OS: Ubuntu 22.04+ (Linux)
- JDK 21 (Android Studio バンドル JBR を推奨、`JAVA_HOME=/opt/android-studio/jbr`)
- Node.js 22+
- Android SDK (`~/Android/Sdk`)、`adb` / `emulator` はフルパス参照 (PATH 未登録)
- Python 3.10+ (verify_atomicity.py 用)
- Rokid Glass + USB ペア済み Android phone (実機テスト時)

詳細は [docs/04-setup.md](./docs/04-setup.md) 参照。

## セットアップ

```bash
# 1. clone (submodule 含む)
git clone --recurse-submodules https://github.com/TakanariShimbo/claude-mobile-hud.git ~/claude-mobile-hud
cd ~/claude-mobile-hud

# 2. Hub / Bridge セットアップ
( cd hub    && npm ci && npm run build && npm test )
( cd bridge && npm ci && npm run build && npm test )

# 3. Android (Phone + Glass) ビルド
export JAVA_HOME=/opt/android-studio/jbr
export PATH=$JAVA_HOME/bin:$PATH
./gradlew :protocol:build :protocol:test
./gradlew :phone:assembleDebug :glass:assembleDebug

# 4. 端末にインストール (adb は ~/Android/Sdk/platform-tools/adb)
ADB=~/Android/Sdk/platform-tools/adb
$ADB install -r phone/build/outputs/apk/debug/phone-debug.apk
$ADB install -r glass/build/outputs/apk/debug/glass-debug.apk
```

## 起動

```bash
# 1. Hub を常駐起動 (別ターミナル推奨)
cd hub && npm start

# 2. (初回のみ) Phone とペアリング: QR を表示して Phone カメラで読取
npm --prefix hub run pair:lan        # LAN 経由
npm --prefix hub run pair:ts         # Tailscale 経由

# 3. claude-mobile-hud で Claude Code セッションを起動
./claude-mobile-hud run safe         # Permission Relay 有効 (Phone で verdict)
# or
./claude-mobile-hud run yolo         # --dangerously-skip-permissions
```

Phone から send / Glass で TAP 録音 → claude に届くまで動作すれば成功。

## テスト

```bash
# Kotlin unit test (protocol / phone / glass)
./gradlew :protocol:test :phone:testDebugUnitTest :glass:testDebugUnitTest

# TypeScript unit test (hub / bridge)
( cd hub && npm test ) && ( cd bridge && npm test )

# Wire parity smoke (Kotlin golden を TS 側で JSON-parse、snake_case 命名一致)
( cd bridge && npm test )            # 上で同時に走る

# AC-09 atomicity self test
python3 tools/test_verify_atomicity.py

# AC-09 atomicity 実機検証 (Phone 接続中)
python3 tools/verify_atomicity.py --from-adb
```

CI (GitHub Actions) は protocol / hub / bridge / phone / glass の 5 ジョブ並列で `lint + test` を走らせる (`.github/workflows/ci.yml`)。

## 開発ループ

CLAUDE.md に詳細な adb / gradle / scrcpy 手順あり。要約:

```bash
ADB=~/Android/Sdk/platform-tools/adb

# 1. コード変更後、APK 再生成 + 接続デバイスへインストール
./gradlew :phone:installDebug
./gradlew :glass:installDebug

# 2. ログ確認
$ADB logcat -d --pid=$($ADB shell pidof com.example.claudemobilehud.phone)
$ADB logcat -d --pid=$($ADB shell pidof com.example.claudemobilehud.glass)

# 3. クラッシュ抜粋
$ADB logcat -d | grep -A 30 "FATAL EXCEPTION" | tail -60

# 4. 端末ミラー (Pixel 8)
scrcpy -s 46031FDJH0026G --max-size 1080 --stay-awake
```

## 設計書

| 文書 | 概要 |
|---|---|
| [docs/01-requirements.md](./docs/01-requirements.md) | 機能要件 / 非機能要件 / スコープ / 受け入れ基準 |
| [docs/02-architecture.md](./docs/02-architecture.md) | コンポーネント責務 / wire protocol / シーケンス / 横断 ADR |
| [docs/03-detailed-design.md](./docs/03-detailed-design.md) | クラス構造 / 状態遷移 / 永続化スキーマ / Phase 4 完了報告 / Phase 5 引き継ぎ |
| [docs/04-setup.md](./docs/04-setup.md) | 開発環境セットアップ詳細 |
| [CLAUDE.md](./CLAUDE.md) | Claude Code / claude.ai を使った開発時の実用コマンド集 |

## 既知の制限 (Phase 5 持ち越し)

- **Hub 単独再起動**: Bridge は Hub 切断で自殺する設計のため、Hub crash 時は wrapper (claude + Bridge) も Ctrl-C で停止 → 再起動する運用 (docs/03 §5.3)。Phase 5 で auto-reconnect + outstanding resend を実装予定。
- **VerdictDispatchService cold-start (NFR-14)**: 実機で完全 cold-start を作るのが困難 (`am force-stop` で通知消失 / `am kill` で FGS 拒否) のため、Phase 5 で AC-09 logcat 解析と一緒に枠組み化する。
- **Hub TLS 終端**: 現状 plain HTTP (debug overlay の cleartext 許可)、Phase 5 で TLS 終端追加予定。
- **wire parity 本格 CI**: 現状は Kotlin golden の snake_case smoke のみ (`bridge/test/wire-golden-smoke.test.ts`)。Phase 5 で zod schema 化 + Kotlin/TS field 一致検証を実装予定。

## 参考

- POC: `~/claude-channel`
- POC のレビュー結果: `~/claude-channel/docs/TODO.md`
