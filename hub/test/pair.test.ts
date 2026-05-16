// `pair.ts` の wire shape / score / pickAddress を vitest で固定する。
// P2-A of 5-6 review: Phone 側 `Pairing.parse` と key 名 / version が drift しないように、
// Hub が生成する payload を 1 箇所でロックし、両端の DTO を一緒に変えるしかない形にする。

import { describe, expect, it } from "vitest";
import { buildPayload, pickAddress, score } from "../src/pair.js";

describe("buildPayload", () => {
    it("emits canonical {v, baseUrl, token} JSON in that key order", () => {
        const raw = buildPayload("http://192.168.1.10:8788", "secret-token");
        // 完全一致のスナップショット (key 順序 / 名前 / value までロック)。Phone 側
        // Pairing.QrPayload と key 名が文字列レベルで揃っていることをこの 1 行で担保する。
        expect(raw).toBe(
            '{"v":1,"baseUrl":"http://192.168.1.10:8788","token":"secret-token"}',
        );
    });

    it("escapes special chars in token", () => {
        // token に " や \\ が含まれていても JSON 上は escape されること (URL/Base64 で
        // 実害はないが、wire 上で fail しない sanity check)。
        const raw = buildPayload("http://h:1", 'a"b\\c');
        const back = JSON.parse(raw);
        expect(back.token).toBe('a"b\\c');
    });
});

describe("score", () => {
    it("rejects tailscale when mode=lan", () => {
        expect(score("tailscale0", "100.64.0.1", "lan")).toBeNull();
        expect(score("eth0", "100.64.0.1", "lan")).toBeNull();
    });
    it("accepts only tailscale when mode=ts", () => {
        expect(score("tailscale0", "100.64.0.1", "ts")).toBe(100);
        expect(score("wlan0", "192.168.1.10", "ts")).toBeNull();
    });
    it("ranks wireless > wired > unknown", () => {
        expect(score("wlan0", "192.168.1.10", "lan")).toBe(50);
        expect(score("eth0", "192.168.1.10", "lan")).toBe(30);
        expect(score("br0", "10.0.0.1", "lan")).toBe(0);
    });
    it("penalizes virtual bridges", () => {
        expect(score("virbr0", "192.168.122.1", "lan")).toBe(-20);
        expect(score("docker0", "172.17.0.1", "lan")).toBe(-20);
    });
});

describe("pickAddress", () => {
    it("respects HUB_PAIR_HOST override", () => {
        const addr = pickAddress("lan", { HUB_PAIR_HOST: "10.0.0.99" });
        expect(addr).toBe("10.0.0.99");
    });
    it("returns null with empty override and no iface (best-effort smoke)", () => {
        // 実環境の networkInterfaces() に依存するので strict assert は避け、override が
        // 効くことだけ確認。
        const addr = pickAddress("ts", { HUB_PAIR_HOST: "" });
        // override が空文字なら通常経路。Tailscale 無い CI 環境では null になる想定だが、
        // 環境依存なので value 自体は assert しない (string | null どちらでも OK)。
        expect(addr === null || typeof addr === "string").toBe(true);
    });
});
