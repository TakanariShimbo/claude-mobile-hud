// Bridge → Hub → SSE / HTTP POST → Bridge の e2e。
// 実 TCP / HTTP server を起動し、テストクライアントから叩く。

import net from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BridgeServer } from "../src/server/BridgeServer.js";
import { HttpServer } from "../src/server/HttpServer.js";
import { ChatRegistry } from "../src/state/ChatRegistry.js";
import { OutstandingPermissions } from "../src/state/OutstandingPermissions.js";
import { SessionRegistry } from "../src/state/SessionRegistry.js";
import { StructuredLog } from "../src/log/StructuredLog.js";
import type { PhoneSseEvent } from "../src/wire/PhoneWire.js";
import type { BridgeToHubMessage, HubToBridgeMessage } from "../src/wire/BridgeWire.js";

interface Harness {
    bridge: BridgeServer;
    http: HttpServer;
    httpPort: number;
    bridgePort: number;
    sessions: SessionRegistry;
    outstanding: OutstandingPermissions;
    chats: ChatRegistry;
    close(): Promise<void>;
}

// 0 番ポートで listen → assignedPort を取り出す helper。
async function pickPort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const srv = net.createServer();
        srv.unref();
        srv.listen(0, "127.0.0.1", () => {
            const addr = srv.address();
            if (addr && typeof addr === "object") {
                const port = addr.port;
                srv.close(() => resolve(port));
            } else {
                srv.close(() => reject(new Error("addr unknown")));
            }
        });
    });
}

async function startHarness(): Promise<Harness> {
    const sessions = new SessionRegistry();
    const outstanding = new OutstandingPermissions();
    const chats = new ChatRegistry();
    const silentSink = (_line: string) => undefined;
    const logger = new StructuredLog("test", "ERROR", silentSink);

    const httpPort = await pickPort();
    const bridgePort = await pickPort();

    const http = new HttpServer({
        sessions,
        chats,
        outstanding,
        dispatchToBridge: (sessionId, msg) => bridge.sendToSession(sessionId, msg),
        logger,
        token: null,
        sseKeepAliveMs: 60_000,
    });
    const bridge = new BridgeServer({
        sessions,
        outstanding,
        phoneBroadcast: (event) => http.broadcast(event),
        logger,
    });

    await Promise.all([
        bridge.listen(bridgePort, "127.0.0.1"),
        http.listen(httpPort, "127.0.0.1"),
    ]);

    return {
        bridge,
        http,
        httpPort,
        bridgePort,
        sessions,
        outstanding,
        chats,
        close: async () => {
            await Promise.allSettled([bridge.close(), http.close()]);
        },
    };
}

// Bridge を装う side。NDJSON フレーミング。
class FakeBridge {
    private buffer = "";
    private socket: net.Socket;
    readonly received: BridgeToHubMessage[] = [];
    private receivedFromHub: HubToBridgeMessage[] = [];

    constructor(port: number) {
        this.socket = net.connect(port, "127.0.0.1");
        this.socket.setEncoding("utf8");
        this.socket.on("data", (chunk: string | Buffer) => {
            this.buffer += typeof chunk === "string" ? chunk : chunk.toString("utf8");
            let nl = this.buffer.indexOf("\n");
            while (nl >= 0) {
                const line = this.buffer.slice(0, nl).trim();
                this.buffer = this.buffer.slice(nl + 1);
                if (line) this.receivedFromHub.push(JSON.parse(line));
                nl = this.buffer.indexOf("\n");
            }
        });
    }

    async untilConnected(): Promise<void> {
        if (this.socket.connecting) {
            await new Promise<void>((resolve, reject) => {
                this.socket.once("connect", () => resolve());
                this.socket.once("error", reject);
            });
        }
    }

    send(msg: BridgeToHubMessage): void {
        this.socket.write(JSON.stringify(msg) + "\n");
    }

    /** Hub から受信した msg のうち、type=`type` の最初の 1 件を timeout 内に取得 */
    async waitFor<T extends HubToBridgeMessage["type"]>(
        type: T,
        timeoutMs = 1_000,
    ): Promise<Extract<HubToBridgeMessage, { type: T }>> {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const hit = this.receivedFromHub.find((m) => m.type === type);
            if (hit) return hit as Extract<HubToBridgeMessage, { type: T }>;
            await new Promise((r) => setTimeout(r, 10));
        }
        throw new Error(`timeout waiting for type=${type}; got: ${JSON.stringify(this.receivedFromHub)}`);
    }

    close(): void {
        this.socket.destroy();
    }
}

// SSE をテキストで読む簡易クライアント。
class SseReader {
    readonly events: PhoneSseEvent[] = [];
    private buffer = "";
    private controller = new AbortController();

    constructor(private readonly url: string) {}

    async start(): Promise<void> {
        const res = await fetch(this.url, { signal: this.controller.signal });
        if (!res.body) throw new Error("no body");
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        (async () => {
            try {
                for (;;) {
                    const { value, done } = await reader.read();
                    if (done) break;
                    this.buffer += decoder.decode(value, { stream: true });
                    this.consume();
                }
            } catch {
                /* abort or close */
            }
        })();
        // 最初のイベント (session_snapshot) が来るまで待つ
        await this.waitForAny();
    }

    private consume(): void {
        let sep = this.buffer.indexOf("\n\n");
        while (sep >= 0) {
            const frame = this.buffer.slice(0, sep);
            this.buffer = this.buffer.slice(sep + 2);
            sep = this.buffer.indexOf("\n\n");

            const dataLines = frame.split("\n").filter((l) => l.startsWith("data:"));
            if (dataLines.length === 0) continue;
            const payload = dataLines.map((l) => l.slice(5).trimStart()).join("\n");
            try {
                this.events.push(JSON.parse(payload) as PhoneSseEvent);
            } catch {
                /* keep-alive comment etc. */
            }
        }
    }

    private async waitForAny(timeoutMs = 1_000): Promise<void> {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            if (this.events.length > 0) return;
            await new Promise((r) => setTimeout(r, 10));
        }
        throw new Error("SSE timed out before first event");
    }

    async waitForType<T extends PhoneSseEvent["type"]>(
        type: T,
        timeoutMs = 1_000,
    ): Promise<Extract<PhoneSseEvent, { type: T }>> {
        const start = Date.now();
        while (Date.now() - start < timeoutMs) {
            const hit = this.events.find((e) => e.type === type);
            if (hit) return hit as Extract<PhoneSseEvent, { type: T }>;
            await new Promise((r) => setTimeout(r, 10));
        }
        throw new Error(`timeout waiting for SSE type=${type}; got: ${JSON.stringify(this.events)}`);
    }

    close(): void {
        this.controller.abort();
    }
}

describe("Hub integration", () => {
    let h: Harness;

    beforeEach(async () => {
        h = await startHarness();
    });

    afterEach(async () => {
        await h.close();
    });

    it("Bridge register → SSE session_snapshot reflects session", async () => {
        const bridge = new FakeBridge(h.bridgePort);
        await bridge.untilConnected();
        bridge.send({ type: "register", session_id: "sess-A", pid: 4242 });
        await bridge.waitFor("ack_register");

        const sse = new SseReader(`http://127.0.0.1:${h.httpPort}/events`);
        await sse.start();

        const snap = await sse.waitForType("session_snapshot");
        expect(snap.active_session_ids).toContain("sess-A");
        const permSnap = await sse.waitForType("permission_snapshot");
        expect(permSnap.request_ids).toEqual([]);

        sse.close();
        bridge.close();
    });

    it("POST /send mints chat_id and forwards to Bridge", async () => {
        const bridge = new FakeBridge(h.bridgePort);
        await bridge.untilConnected();
        bridge.send({ type: "register", session_id: "sess-A", pid: 1 });
        await bridge.waitFor("ack_register");

        const resp = await fetch(`http://127.0.0.1:${h.httpPort}/send`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text: "hello" }),
        });
        expect(resp.status).toBe(200);
        const body = (await resp.json()) as { chat_id: string; session_id?: string };
        expect(body.chat_id).toMatch(/^chat-\d+$/);
        expect(body.session_id).toBe("sess-A");

        const fwd = await bridge.waitFor("send");
        expect(fwd.text).toBe("hello");
        expect(fwd.chat_id).toBe(body.chat_id);

        bridge.close();
    });

    it("Bridge permission → SSE permission; POST /permission → Bridge verdict; AD-13 snapshot replays", async () => {
        const bridge = new FakeBridge(h.bridgePort);
        await bridge.untilConnected();
        bridge.send({ type: "register", session_id: "sess-A", pid: 1 });
        await bridge.waitFor("ack_register");

        // SSE 接続 1 (1 件目の permission を見たい)
        const sse1 = new SseReader(`http://127.0.0.1:${h.httpPort}/events`);
        await sse1.start();
        await sse1.waitForType("session_snapshot");

        bridge.send({
            type: "permission",
            request_id: "req-1",
            session_id: "sess-A",
            tool_name: "Bash",
            description: "run ls",
            input_preview: "ls",
        });
        const perm = await sse1.waitForType("permission");
        expect(perm.request_id).toBe("req-1");
        expect(h.outstanding.has("req-1")).toBe(true);

        // 別の SSE クライアントで再接続 → AD-13: snapshot で req-1 が再 push される
        const sse2 = new SseReader(`http://127.0.0.1:${h.httpPort}/events`);
        await sse2.start();
        const permSnap2 = await sse2.waitForType("permission_snapshot");
        expect(permSnap2.request_ids).toEqual(["req-1"]);
        const replayed = await sse2.waitForType("permission");
        expect(replayed.request_id).toBe("req-1");

        // verdict
        const verdictResp = await fetch(`http://127.0.0.1:${h.httpPort}/permission`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ request_id: "req-1", behavior: "allow" }),
        });
        expect(verdictResp.status).toBe(200);
        const verdict = await bridge.waitFor("permission_verdict");
        expect(verdict.behavior).toBe("allow");
        expect(h.outstanding.has("req-1")).toBe(false);

        // 2 回目の verdict は 410
        const second = await fetch(`http://127.0.0.1:${h.httpPort}/permission`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ request_id: "req-1", behavior: "allow" }),
        });
        expect(second.status).toBe(410);
        const errBody = (await second.json()) as { error_code: string };
        expect(errBody.error_code).toBe("permission_gone");

        sse1.close();
        sse2.close();
        bridge.close();
    });

    it("Bridge disconnect aborts outstanding (FR-HU-13) + emits session_inactive", async () => {
        const bridge = new FakeBridge(h.bridgePort);
        await bridge.untilConnected();
        bridge.send({ type: "register", session_id: "sess-X", pid: 1 });
        await bridge.waitFor("ack_register");

        const sse = new SseReader(`http://127.0.0.1:${h.httpPort}/events`);
        await sse.start();
        await sse.waitForType("session_snapshot");

        bridge.send({
            type: "permission",
            request_id: "req-99",
            session_id: "sess-X",
            tool_name: "Bash",
            description: "x",
            input_preview: "x",
        });
        await sse.waitForType("permission");

        bridge.close();

        const abort = await sse.waitForType("permission_abort");
        expect(abort.request_id).toBe("req-99");
        const inactive = await sse.waitForType("session_inactive");
        expect(inactive.session_id).toBe("sess-X");
        expect(h.outstanding.has("req-99")).toBe(false);

        sse.close();
    });

    it("X-Token auth rejects missing token (401 + auth_failed)", async () => {
        // 別の harness を token 付きで立てる
        await h.close();
        const sessions = new SessionRegistry();
        const outstanding = new OutstandingPermissions();
        const chats = new ChatRegistry();
        const silentSink = (_line: string) => undefined;
        const logger = new StructuredLog("test", "ERROR", silentSink);
        const httpPort = await pickPort();
        const bridgePort = await pickPort();
        const http = new HttpServer({
            sessions,
            chats,
            outstanding,
            dispatchToBridge: () => false,
            logger,
            token: "secret-xyz",
            sseKeepAliveMs: 60_000,
        });
        const bridge = new BridgeServer({
            sessions,
            outstanding,
            phoneBroadcast: () => undefined,
            logger,
        });
        await Promise.all([
            bridge.listen(bridgePort, "127.0.0.1"),
            http.listen(httpPort, "127.0.0.1"),
        ]);

        try {
            const noToken = await fetch(`http://127.0.0.1:${httpPort}/send`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ text: "x" }),
            });
            expect(noToken.status).toBe(401);
            const body = (await noToken.json()) as { error_code: string };
            expect(body.error_code).toBe("auth_failed");

            const withToken = await fetch(`http://127.0.0.1:${httpPort}/send`, {
                method: "POST",
                headers: { "Content-Type": "application/json", "X-Token": "secret-xyz" },
                body: JSON.stringify({ text: "x" }),
            });
            // session が無いので session_not_active になるはず
            expect(withToken.status).toBe(400);
            const ok = (await withToken.json()) as { error_code: string };
            expect(ok.error_code).toBe("session_not_active");
        } finally {
            await Promise.allSettled([bridge.close(), http.close()]);
            // afterEach の h.close() を unsupervised にしないよう、ダミーを差し戻す
            h = await startHarness();
        }
    });
});
