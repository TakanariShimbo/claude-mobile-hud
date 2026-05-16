// `npm run pair lan|ts` で実行。Hub の baseUrl + token を JSON 化して QR としてターミナル
// に表示する。Phone 側 `SettingsDialog` の「QR スキャン」ボタンで読み取り → 自動入力。
//
// payload shape (Phone 側 `phone/data/Pairing.kt::QrPayload` と一致させる必要あり):
//   { "v": 1, "baseUrl": "http://<host>:<port>", "token": "<x-token>" }
//
// 引数:
//   - `lan`: Wi-Fi / Ethernet の IPv4 (Tailscale を除外)
//   - `ts`:  Tailscale (100.x.x.x もしくは `tailscale*` iface)
//
// **P2-A/B of 5-6 review**: payload 生成 / address pick / score を **named export** にして
//   vitest からゴールデン的に検証する (両端 drift が CI で検出される)。`main()` 側は I/O だけ。

import { networkInterfaces } from "node:os";
import qrcode from "qrcode-terminal";
import { loadConfig } from "./config/Config.js";

export type Mode = "lan" | "ts";

const USAGE = [
    "Usage: pair <lan|ts>",
    "  lan  Wi-Fi / Ethernet IPv4 (skips Tailscale)",
    "  ts   Tailscale (100.x.x.x or tailscale* iface)",
].join("\n");

const isTailscale = (name: string, addr: string): boolean =>
    name.startsWith("tailscale") || addr.startsWith("100.");

// より「使われそうな」NIC を高 score にする (sort 後の最高得点を採用):
//   - tailscale (mode=ts のときのみ): 100
//   - wireless (wl* / wlan*): 50
//   - wired (en* / eth*): 30
//   - virbr / docker (仮想 bridge): -20 で除外寄り
//   - その他: 0
//
// P2-F of 5-6 review: `en*` prefix は Linux の `enp` / `eno` / `ens` / `enx`(USB tether) も
// 拾うため、複数 IPv4 を持つ host では同点の中から先頭が選ばれて Phone から到達できない
// アドレスが当たることがある。実害時は `HUB_PAIR_HOST` env で明示 override する。
export function score(name: string, addr: string, mode: Mode): number | null {
    const ts = isTailscale(name, addr);
    if (mode === "lan" && ts) return null;
    if (mode === "ts" && !ts) return null;
    if (ts) return 100;
    if (name.startsWith("wl") || name.startsWith("wlan")) return 50;
    if (name.startsWith("en") || name.startsWith("eth")) return 30;
    if (name.startsWith("virbr") || name.startsWith("docker")) return -20;
    return 0;
}

export function pickAddress(mode: Mode, env: NodeJS.ProcessEnv = process.env): string | null {
    // P2-F: env override が最優先 (NIC 自動判定で外れたケースの脱出口)。
    if (env.HUB_PAIR_HOST && env.HUB_PAIR_HOST.length > 0) return env.HUB_PAIR_HOST;

    const candidates: { addr: string; score: number }[] = [];
    for (const [name, addrs] of Object.entries(networkInterfaces())) {
        if (!addrs) continue;
        for (const a of addrs) {
            if (a.family !== "IPv4" || a.internal) continue;
            const s = score(name, a.address, mode);
            if (s == null) continue;
            candidates.push({ addr: a.address, score: s });
        }
    }
    candidates.sort((x, y) => y.score - x.score);
    return candidates[0]?.addr ?? null;
}

/**
 * QR の payload (= Phone 側 `Pairing.QrPayload` と shape を一致させる必要あり)。
 * 単独 export してテストで wire shape を fix する (両端 drift の CI 検出)。
 */
export function buildPayload(baseUrl: string, token: string): string {
    return JSON.stringify({ v: 1, baseUrl, token });
}

function main(): void {
    const arg = process.argv[2];
    if (arg !== "lan" && arg !== "ts") {
        console.error(USAGE);
        process.exit(1);
    }
    const config = loadConfig();
    if (!config.token) {
        console.error(
            "Hub config に token が設定されていません。`HUB_TOKEN` 環境変数 or config を設定してください。",
        );
        process.exit(2);
    }
    const addr = pickAddress(arg);
    if (!addr) {
        const where = arg === "ts" ? "Tailscale" : "LAN";
        console.error(`No ${where} address found. Is the interface up?`);
        process.exit(3);
    }
    const baseUrl = `http://${addr}:${config.httpPort}`;
    // P1-A: guard 通過後の type narrowing を明示。token は string であることを ensure。
    const token: string = config.token;
    const payload = buildPayload(baseUrl, token);

    // P3-B of 5-6 review: console.log に統一 (write は改行制御以外に必要性なし)。
    console.log();
    console.log("Scan with the Claude Mobile HUD Android app:");
    console.log();
    qrcode.generate(payload, { small: true });
    console.log();
    console.log(`  base URL: ${baseUrl}  (${arg})`);
    // P3-C of 5-6 review: terminal screen のテキスト表示は mask する。**ただし QR 画像
    // 自体には full token が乗っている**ため、画面を撮影されると token は漏れる
    // (terminal 録画から QR を抽出される)。マスク表示はあくまで「QR を撮影しない録画」
    // での緩和措置である点を明示。
    const masked = `${token.slice(0, 4)}...${token.slice(-4)}`;
    console.log(`  token:    ${masked}  (${token.length} chars; full は QR 画像から抽出可能)`);
    console.log();
}

// vitest から import したときに main() が走らないように、CLI 起動時のみ実行。
// `tsx src/pair.ts` 直接実行と `import` の両方を成立させる。
const isDirectExec = import.meta.url === `file://${process.argv[1]}`;
if (isDirectExec) main();
