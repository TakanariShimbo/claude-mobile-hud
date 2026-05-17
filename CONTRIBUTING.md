# Contributing to claude-mobile-hud

PR / Issue 歓迎です。本プロジェクトは **個人ツールとしての完成度を優先**しているので、変更の前に方向性を擦り合わせる方がスムーズです。

## 設計書を先に読む

実装より先に設計が書かれているプロジェクトです。修正提案 / 機能追加の前に該当部分の設計書を確認してください:

- `docs/01-requirements.md` — 機能要件 / 非機能要件 / 受け入れ基準
- `docs/02-architecture.md` — コンポーネント責務 / wire protocol / シーケンス
- `docs/03-detailed-design.md` — クラス構造 / 状態遷移 / 永続化 / Phase 4 完了報告 / Phase 5 引き継ぎ
- `docs/04-setup.md` — 開発環境セットアップ

## 開発ワークフロー

1. **Issue を立てる** — 変更内容と目的をテンプレートに沿って記述
2. **ローカルで開発** — `CLAUDE.md` に adb / gradle / scrcpy の実用コマンド集あり
3. **テストを通す** — Kotlin / TypeScript / Python の全 unit test が green であること
4. **PR を作成** — 変更点 + 設計書のどこに該当するか + 動作確認内容を本文に書く

## テスト要件

最低限 CI が green であること:

```bash
# Kotlin
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :protocol:test :phone:testDebugUnitTest :glass:testDebugUnitTest

# TypeScript
( cd hub && npm test ) && ( cd bridge && npm test )

# Python (Phase 5 計測ツール)
python3 tools/test_verify_atomicity.py
python3 tools/test_measure_verdict_latency.py
python3 tools/test_measure_reconnect_latency.py
```

GitHub Actions が PR で全自動実行します (`.github/workflows/ci.yml`)。

## コミット規約

- 1 つの commit に 1 つの論理変更を入れる
- メッセージは Conventional Commits 風に: `type(scope): subject`
  - `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `chore`
  - 例: `fix(phone/ui): Settings token 入力で non-ASCII を視覚エラー`
- 設計書 (`docs/03` 等) に新規追加 / 変更がある場合は同じ commit に同梱

## レビュー

実装変更には独立レビューを通す慣習があります (drafter + reviewer の 2 人体制)。`/agents/code-reviewer` 相当の独立確認を経て merge することを推奨。

## 範囲外 / 「やらない」決定

`docs/03 §10.4.4` に Phase 5 で「やらない」と決めた項目があります (例: Bridge auto-reconnect)。これらは設計判断として確定しているので、再提案する場合は強い理由 (新規ユースケース / セキュリティ問題等) を併記してください。

## ライセンス

MIT。詳しくは [LICENSE](./LICENSE) を参照。寄与した内容も同ライセンスで取り込まれます。
