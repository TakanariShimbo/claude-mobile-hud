# claude-mobile-hud

PC の Claude Code を、**Android phone と Rokid Glass HUD** から操作するモバイルクライアント群。

- **phone** (Android): 入力ハブ。テキスト / 音声 / 画像 / permission 応答 / セッション切替
- **glass** (Rokid HUD companion): 視界の HUD に reply とツール承認を表示、ジェスチャで操作
- **hub** / **bridge** (PC 側): phone との通信中継と Claude Code Channel への橋渡し

## このリポジトリの位置づけ

`~/claude-channel` の POC を、**ウォーターフォール流れで設計し直して再構築する第 2 世代**。POC で得た知見と痛みを設計書として書き下した上で、再実装する。

### 移行方針

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 0 | POC レビュー / 課題抽出 | 完了 (前リポジトリの `docs/TODO.md`) |
| Phase 1 | 要件定義 | 未着手 |
| Phase 2 | 基本設計 (アーキテクチャ / wire protocol) | 未着手 |
| Phase 3 | 詳細設計 | 未着手 |
| Phase 4 | 実装 | 未着手 |
| Phase 5 | テスト | 未着手 |
| Phase 6 | リリース・移行 | 未着手 |

## 参考

- POC: `~/claude-channel`
- POC のレビュー結果: `~/claude-channel/docs/TODO.md`
