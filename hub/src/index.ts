// docs/03 §5.6: Hub daemon entry。HTTP/SSE + Bridge NDJSON を 1 プロセスで wire する。

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

    // docs/03 §5.6.1: http と bridge の循環参照はクロージャで結ぶ。
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

    // docs/03 §5.6.2: 片肺 listen 失敗時に socket を残さないため allSettled + cleanup。
    const bridgeListen = bridge.listen(config.bridgePort, "127.0.0.1");
    const httpListen = http.listen(config.httpPort, "0.0.0.0");
    const results = await Promise.allSettled([bridgeListen, httpListen]);
    const failures = results.filter((r): r is PromiseRejectedResult => r.status === "rejected");
    if (failures.length > 0) {
        const cleanups: Promise<unknown>[] = [];
        if (results[0]!.status === "fulfilled") cleanups.push(bridge.close());
        if (results[1]!.status === "fulfilled") cleanups.push(http.close());
        await Promise.allSettled(cleanups);
        throw failures[0]!.reason;
    }

    // docs/03 §5.6.3: SIGTERM/SIGINT 2 入口、idempotency は server.close() 任せ。
    const shutdown = async (signal: string): Promise<void> => {
        root.info("shutdown", { signal });
        await Promise.allSettled([bridge.close(), http.close()]);
        process.exit(0);
    };
    process.on("SIGTERM", () => void shutdown("SIGTERM"));
    process.on("SIGINT", () => void shutdown("SIGINT"));
}

// docs/03 §5.6.4: Logger 不在の bootstrap 失敗用 fatal path (stderr 直書き)。
main().catch((err) => {
    process.stderr.write(`[hub] fatal: ${(err as Error).stack ?? String(err)}\n`);
    process.exit(1);
});
