// Bridge entry — Claude Code から子プロセスで起動される。
// 1. SessionDetector で親の cmdline から --session-id を抽出
// 2. HubClient で Hub に TCP NDJSON 接続 + register
// 3. McpServer で stdio MCP を立ち上げ Claude と話す
// 4. SIGTERM/SIGINT / MCP close で graceful shutdown + inbox cleanup
//
// Phase 3 §6。

import { HubClient } from "./HubClient.js";
import { ImageStaging } from "./ImageStaging.js";
import { McpServer } from "./McpServer.js";
import { SessionDetector } from "./SessionDetector.js";
import { StructuredLog, type LogLevel } from "./log/StructuredLog.js";

interface BridgeConfig {
    hubHost: string;
    hubPort: number;
    inboxDir: string;
    logLevel: LogLevel;
}

const LOG_LEVELS: readonly LogLevel[] = ["DEBUG", "INFO", "WARN", "ERROR"] as const;

function parseLogLevel(raw: string | undefined): LogLevel {
    if (raw && (LOG_LEVELS as readonly string[]).includes(raw)) return raw as LogLevel;
    return "INFO";
}

function loadConfig(): BridgeConfig {
    return {
        hubHost: process.env.HUB_HOST ?? "127.0.0.1",
        hubPort: Number.parseInt(process.env.HUB_BRIDGE_PORT ?? "8787", 10),
        inboxDir: process.env.BRIDGE_INBOX_DIR ?? ImageStaging.defaultPath(),
        logLevel: parseLogLevel(process.env.BRIDGE_LOG_LEVEL),
    };
}

async function main(): Promise<void> {
    const config = loadConfig();
    const root = new StructuredLog("bridge", config.logLevel);
    root.info("startup", {
        hub_host: config.hubHost,
        hub_port: config.hubPort,
        inbox_dir: config.inboxDir,
    });

    const detector = new SessionDetector({ logger: root.withTag("session") });
    const sessionId = await detector.detect();

    const images = new ImageStaging(config.inboxDir, root.withTag("inbox"));
    await images.prepare();

    let mcp: McpServer | null = null;
    // hub は callbacks 内 (onClose) で同期発火し得るので、`const hub = new HubClient(...)`
    // 宣言前にハンドラから参照される TDZ 地雷を避ける (P1-4)。
    let hub: HubClient | null = null;
    let shuttingDown = false;
    const shutdown = async (signal: string): Promise<void> => {
        if (shuttingDown) return;
        shuttingDown = true;
        root.info("shutdown", { signal });
        mcp?.close();
        hub?.close();
        await images.cleanup();
        process.exit(0);
    };

    hub = new HubClient({
        host: config.hubHost,
        port: config.hubPort,
        sessionId,
        logger: root.withTag("hub"),
        callbacks: {
            onSend: (msg) => mcp?.deliverSend(msg),
            onPermissionVerdict: (msg) => mcp?.deliverPermissionVerdict(msg),
            onClose: (reason) => {
                root.info("hub_closed", { reason });
                if (reason === "remote") void shutdown("hub_remote_close");
            },
        },
    });
    await hub.connect();

    mcp = new McpServer({
        sessionId,
        hub,
        images,
        logger: root.withTag("mcp"),
        onClose: () => void shutdown("mcp_close"),
    });
    await mcp.start();

    process.on("SIGTERM", () => void shutdown("SIGTERM"));
    process.on("SIGINT", () => void shutdown("SIGINT"));
}

main().catch((err) => {
    process.stderr.write(`[bridge] fatal: ${(err as Error).stack ?? String(err)}\n`);
    process.exit(1);
});
