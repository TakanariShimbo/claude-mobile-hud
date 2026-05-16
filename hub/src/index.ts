// Hub daemon entry. Phase 3 §5。
// PC 上で長時間動き、Phone とは HTTP/SSE、Bridge とは TCP NDJSON で繋がる。

import { loadConfig } from "./config/Config.js";
import { StructuredLog, type LogLevel } from "./log/StructuredLog.js";
import { BridgeServer } from "./server/BridgeServer.js";
import { HttpServer } from "./server/HttpServer.js";
import { ChatRegistry } from "./state/ChatRegistry.js";
import { OutstandingPermissions } from "./state/OutstandingPermissions.js";
import { SessionRegistry } from "./state/SessionRegistry.js";

async function main(): Promise<void> {
    const config = loadConfig();
    const root = new StructuredLog("hub", (process.env.HUB_LOG_LEVEL as LogLevel) ?? "INFO");
    root.info("startup", {
        http_port: config.httpPort,
        bridge_port: config.bridgePort,
        auth: config.token ? "x_token" : "off",
    });

    const sessions = new SessionRegistry();
    const chats = new ChatRegistry();
    const outstanding = new OutstandingPermissions();

    const http = new HttpServer({
        sessions,
        chats,
        outstanding,
        dispatchToBridge: (sessionId, msg) => bridge.sendToSession(sessionId, msg),
        logger: root.withTag("http"),
        token: config.token,
        sseKeepAliveMs: config.sseKeepAliveMs,
    });
    const bridge = new BridgeServer({
        sessions,
        outstanding,
        phoneBroadcast: (event) => http.broadcast(event),
        logger: root.withTag("bridge"),
    });

    await Promise.all([
        bridge.listen(config.bridgePort, "127.0.0.1"),
        http.listen(config.httpPort, "0.0.0.0"),
    ]);

    const shutdown = async (signal: string): Promise<void> => {
        root.info("shutdown", { signal });
        await Promise.allSettled([bridge.close(), http.close()]);
        process.exit(0);
    };
    process.on("SIGTERM", () => void shutdown("SIGTERM"));
    process.on("SIGINT", () => void shutdown("SIGINT"));
}

main().catch((err) => {
    process.stderr.write(`[hub] fatal: ${(err as Error).stack ?? String(err)}\n`);
    process.exit(1);
});
