# claude-mobile-hud

PC の Claude Code を、**Android phone と Rokid Glass HUD** から操作するモバイルクライアント群。

- **phone** (Android): 入力ハブ。テキスト / 音声 / 画像 / permission 応答 / セッション切替
- **glass** (Rokid HUD companion): 視界の HUD に reply とツール承認を表示、ジェスチャで操作
- **hub** / **bridge** (PC 側): phone との通信中継と Claude Code Channel への橋渡し
- **`:protocol`** (Kotlin library): phone / glass 間の wire 型定義 (Phase 3 §2 / AD-02)

## このリポジトリの位置づけ

`~/claude-channel` の POC を、**ウォーターフォール流れで設計し直して再構築する第 2 世代**。POC で得た知見と痛みを設計書として書き下した上で、再実装する。

### 移行方針

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | POC レビュー / 課題抽出 | 完了 (前リポジトリの `docs/TODO.md`) |
| Phase 1 | 要件定義 | 完了 (`docs/01-requirements.md` Rev 5) |
| Phase 2 | 基本設計 (アーキテクチャ / wire protocol) | 完了 (`docs/02-architecture.md` Rev 5) |
| Phase 3 | 詳細設計 | 完了 (`docs/03-detailed-design.md` Rev 3) |
| **Phase 4 着手前** | **環境セットアップ** | **完了 (`docs/04-setup.md`)** |
| Phase 4 | 実装 | 未着手 |
| Phase 5 | テスト | 未着手 |
| Phase 6 | リリース・移行 | 未着手 |

## ディレクトリ構成

```
~/claude-mobile-hud/
├── docs/                       設計書
├── settings.gradle.kts         Gradle root
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml      バージョンカタログ
│   └── wrapper/
├── gradlew, gradlew.bat
├── protocol/                   Kotlin library subproject (:protocol)
├── (phone/  ← Phase 4 着手時に Android Studio で初期化)
├── (glass/  ← Phase 4 着手時に Android Studio で初期化)
├── cxrglobal/                  git submodule (CXR-L SDK ラッパー)
├── hub/                        Hub (TypeScript, Node)
├── bridge/                     Bridge (TypeScript, Node)
├── claude-mobile-hud           ディスパッチャ CLI (Phase 4 で本実装)
├── tools/
│   └── verify_atomicity.py     NFR-13 自動検証ランナー
│   (QR pairing CLI は hub/src/pair.ts に移動: `npm --prefix hub run pair:lan`)
└── .github/workflows/ci.yml    GitHub Actions CI
```

## 着手手順

詳細は [docs/04-setup.md](./docs/04-setup.md) を参照。要約:

```bash
# 1. clone (submodule 含む)
git clone --recurse-submodules https://github.com/TakanariShimbo/claude-mobile-hud.git ~/claude-mobile-hud
cd ~/claude-mobile-hud

# 2. Hub / Bridge セットアップ
( cd hub    && npm install && npm run build && npm test )
( cd bridge && npm install && npm run build && npm test )

# 3. :protocol ビルド確認
./gradlew :protocol:build

# 4. Phone / Glass を Android Studio で scaffold (docs/04-setup.md §5)
#    → Android Studio で phone/ と glass/ を作成し、Gradle root の settings.gradle.kts に追加
```

## 設計書

| 文書 | 概要 |
|---|---|
| [01-requirements.md](./docs/01-requirements.md) | 機能要件 / 非機能要件 / スコープ / 受け入れ基準 |
| [02-architecture.md](./docs/02-architecture.md) | コンポーネント責務 / wire protocol / シーケンス / 横断 ADR |
| [03-detailed-design.md](./docs/03-detailed-design.md) | クラス構造 / 状態遷移 / 永続化スキーマ |
| [04-setup.md](./docs/04-setup.md) | 開発環境セットアップ |

## 参考

- POC: `~/claude-channel`
- POC のレビュー結果: `~/claude-channel/docs/TODO.md`
