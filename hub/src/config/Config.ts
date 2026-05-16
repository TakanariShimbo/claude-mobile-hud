// Hub の起動設定。env / CLI から読む。Phase 3 §5.1 (config/)。

export interface HubConfig {
    /** Phone 向け HTTP/SSE port (default 8788) */
    httpPort: number;
    /** Bridge 向け NDJSON TCP port (default 8787、loopback) */
    bridgePort: number;
    /** X-Token (Phone→Hub 認証。NFR-20)。未設定なら認証スキップ (dev only) */
    token: string | null;
    /** SSE keep-alive 間隔 ms (default 15s) */
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
    const token = env.HUB_TOKEN && env.HUB_TOKEN.length > 0 ? env.HUB_TOKEN : null;
    const sseKeepAliveMs = parseIntOrDefault(env.HUB_SSE_KEEPALIVE_MS, DEFAULT_CONFIG.sseKeepAliveMs);

    return { httpPort, bridgePort, token, sseKeepAliveMs };
}

function parseIntOrDefault(raw: string | undefined, fallback: number): number {
    if (!raw) return fallback;
    const n = Number.parseInt(raw, 10);
    return Number.isFinite(n) && n > 0 ? n : fallback;
}
