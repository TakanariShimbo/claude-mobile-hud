// docs/03 §5.7: env から Hub config を読む pure module。

export interface HubConfig {
    httpPort: number;
    bridgePort: number;
    /** docs/03 §5.7.2: null = auth off (dev only)。 */
    token: string | null;
    sseKeepAliveMs: number;
}

export const DEFAULT_CONFIG: HubConfig = {
    httpPort: 8788,
    bridgePort: 8787,
    token: null,
    sseKeepAliveMs: 15_000,
};

export function loadConfig(env: NodeJS.ProcessEnv = process.env): HubConfig {
    const httpPort = parseIntOrDefault(env.HUB_HTTP_PORT, DEFAULT_CONFIG.httpPort);
    const bridgePort = parseIntOrDefault(env.HUB_BRIDGE_PORT, DEFAULT_CONFIG.bridgePort);
    // docs/03 §5.7.2: 空文字 / 未設定は null (= auth skip)。
    const token = env.HUB_TOKEN && env.HUB_TOKEN.length > 0 ? env.HUB_TOKEN : null;
    const sseKeepAliveMs = parseIntOrDefault(env.HUB_SSE_KEEPALIVE_MS, DEFAULT_CONFIG.sseKeepAliveMs);

    return { httpPort, bridgePort, token, sseKeepAliveMs };
}

// docs/03 §5.7.3: 0 / 負 / NaN / 文字列は fail-soft で default に倒す。
function parseIntOrDefault(raw: string | undefined, fallback: number): number {
    if (!raw) return fallback;
    const n = Number.parseInt(raw, 10);
    return Number.isFinite(n) && n > 0 ? n : fallback;
}
