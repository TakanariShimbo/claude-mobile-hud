# TODO / 持ち越し事項

`claude-mobile-hud` の Phase 5 完了時点で残った持ち越し事項、および Phase 1–6 の進捗履歴。

## 持ち越し (forward-looking)

| 項目 | 現状 | 引き継ぎ方針 |
|---|---|---|
| **Hub 単独再起動** | Bridge は Hub 切断で自殺する設計のため、Hub crash 時は wrapper (claude + Bridge) も Ctrl-C で停止 → 再起動する運用 (docs/03 §5.3)。 | auto-reconnect + outstanding resend を実装し、Hub だけを再起動できるようにする。 |
| **VerdictDispatchService cold-start (NFR-14)** | 実機で完全 cold-start を再現するのが困難 (`am force-stop` で通知消失 / `am kill` で FGS 拒否)。 | AC-09 logcat 解析と一緒に枠組み化する。 |
| **Hub TLS 終端** | 現状 plain HTTP (debug overlay の cleartext 許可)。 | Hub に TLS 終端を追加し、Phone 側 cleartext 許可を撤去する。 |
| **wire parity 本格 CI** | 現状は Kotlin golden の snake_case smoke のみ (`bridge/test/wire-golden-smoke.test.ts`)。 | zod schema 化 + Kotlin/TS field 一致検証で本格的な parity gate を CI に組み込む。 |

## 進捗履歴

設計書を先に書いてから実装するウォーターフォール構成。

| フェーズ | 内容 | 状態 |
|---|---|---|
| Phase 1 | 要件定義 | ✅ 完了 (`docs/01-requirements.md`) |
| Phase 2 | 基本設計 (アーキテクチャ / wire protocol) | ✅ 完了 (`docs/02-architecture.md`) |
| Phase 3 | 詳細設計 | ✅ 完了 (`docs/03-detailed-design.md`) |
| Phase 4 | 実装 | ✅ 完了 (CI green) |
| Phase 5 | テスト | ✅ 完了 (test infra + AC-09 verifier + NFR 計測ツール + 運用 subcommand) |
| Phase 6 | リリース | ✅ v1.1.x を GitHub Releases に公開 |
