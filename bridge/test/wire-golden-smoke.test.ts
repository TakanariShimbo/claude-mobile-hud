// P1-1 of AC-05 (軽量 smoke): protocol module 配下の Kotlin golden JSON が
// TS 側で JSON-parse 可能で、round-trip (parse → stringify → parse) で構造が
// 維持されることを CI で押さえる。NFR-50 (drift 検出) の最小限の機械チェック。
//
// 制限:
//   - Kotlin golden は Phone↔Glass wire (両側 Kotlin) で、TS 側で扱う Phone↔Hub
//     / Bridge↔Hub の wire と直接対応するわけではない。本テストはあくまで
//     「JSON 構造として valid + snake_case 命名が両側で揃っている」smoke。
//   - field 単位の semantic parity (`pending_permission.request_id` の存在等)
//     を rigorous に押さえるのは Phase 5 §7.6 wire parity CI で zod schema 化
//     とセットで実装する (本書 §10.4.2 引き継ぎ)。

import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const GOLDEN_DIR = path.resolve(
    __dirname,
    "../../protocol/src/test/golden/kotlin",
);

describe("wire golden smoke (Kotlin → TS)", () => {
    const goldenFiles = fs.existsSync(GOLDEN_DIR)
        ? fs.readdirSync(GOLDEN_DIR).filter((f) => f.endsWith(".json")).sort()
        : [];

    it("has at least one golden file", () => {
        expect(goldenFiles.length).toBeGreaterThan(0);
    });

    for (const file of goldenFiles) {
        it(`parses and round-trips ${file}`, () => {
            const raw = fs.readFileSync(path.join(GOLDEN_DIR, file), "utf8");
            const parsed = JSON.parse(raw);
            // round-trip: 再 stringify した結果を parse して構造が同一であること。
            // JSON object key の順序や空白の差は無視される (object equality)。
            const roundTripped = JSON.parse(JSON.stringify(parsed));
            expect(roundTripped).toEqual(parsed);
        });

        it(`uses only snake_case top-level fields (${file})`, () => {
            const raw = fs.readFileSync(path.join(GOLDEN_DIR, file), "utf8");
            const parsed = JSON.parse(raw) as Record<string, unknown>;
            const camelCaseKeys = Object.keys(parsed).filter(
                (k) => /[a-z][A-Z]/.test(k),
            );
            expect(
                camelCaseKeys,
                `${file} に camelCase top-level field がある: ${camelCaseKeys.join(", ")}`,
            ).toEqual([]);
        });
    }
});
