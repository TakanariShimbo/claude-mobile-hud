// docs/03 §6.4: Bridge entry — Claude Code から stdio 子プロセスで起動。
// 起動順序 / shutdown idempotency / TDZ 回避は §6.4.1〜§6.4.5 を参照。

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

    // docs/03 §6.4.1: detect → prepare → connect → start の順序を厳守。
    const detector = new SessionDetector({ logger: root.withTag("session") });
    const sessionId = await detector.detect();

    const images = new ImageStaging(config.inboxDir, root.withTag("inbox"));
    await images.prepare();

    // docs/03 §6.4.2: callback 内参照が TDZ にならないよう mcp/hub を null で先宣言 (P1-4)。
    let mcp: McpServer | null = null;
    let hub: HubClient | null = null;
    let shuttingDown = false;
    const shutdown = async (signal: string): Promise<void> => {
        // docs/03 §6.4.3: 多重発火 (signal + mcp_close 並走) を idempotent に。
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
                // docs/03 §6.4.4: remote close = Hub 死亡 → Bridge も自殺 (§5.3 と対)。
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

// docs/03 §6.4.5: Logger 不在の bootstrap 失敗用 fatal path (stderr 直書き)。
main().catch((err) => {
    process.stderr.write(`[bridge] fatal: ${(err as Error).stack ?? String(err)}\n`);
    process.exit(1);
});
