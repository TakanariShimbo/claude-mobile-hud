// docs/03 §5.5: `npm run pair lan|ts` で Hub の baseUrl + token を QR 表示。
// Phone `Pairing.QrPayload` (§3.2.6.2) と shape を一致させる契約。

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

/** docs/03 §5.5.2: NIC 重み付け。`en*` wide-net の脱出口は `HUB_PAIR_HOST` env。 */
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
    // docs/03 §5.5.2: env override は最優先 short-circuit。
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

/** docs/03 §5.5.3: Phone `Pairing.QrPayload` と key 順までロック。`pair.test.ts` でゴールデン化。 */
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
    const token: string = config.token;
    const payload = buildPayload(baseUrl, token);

    console.log();
    console.log("Scan with the Claude Mobile HUD Android app:");
    console.log();
    qrcode.generate(payload, { small: true });
    console.log();
    console.log(`  base URL: ${baseUrl}  (${arg})`);
    // docs/03 §5.5.4: terminal mask は録画緩和のみ。QR 画像には full token が乗る。
    const masked = `${token.slice(0, 4)}...${token.slice(-4)}`;
    console.log(`  token:    ${masked}  (${token.length} chars; full は QR 画像から抽出可能)`);
    console.log();
}

// docs/03 §5.5.5: vitest import で main() を走らせない CLI 起動 guard。
const isDirectExec = import.meta.url === `file://${process.argv[1]}`;
if (isDirectExec) main();
