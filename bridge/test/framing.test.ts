// HubClient の NDJSON フレーミング境界 + pre-ack FIFO 順序保証テスト。
// 実 TCP を立てて Bridge を繋ぐ。Hub 側は使わず生 socket で writeback する。

import net from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { HubClient } from "../src/HubClient.js";
import { StructuredLog } from "../src/log/StructuredLog.js";
import type {
    BridgeToHubMessage,
    SendMessage,
    PermissionVerdictMessage,
} from "../src/wire/HubWire.js";

const silent = new StructuredLog("test", "ERROR", () => undefined);

interface RawHub {
    port: number;
    server: net.Server;
    connections: net.Socket[];
    inbound: BridgeToHubMessage[];
    close(): Promise<void>;
}

async function startRawHub(): Promise<RawHub> {
    const connections: net.Socket[] = [];
    const inbound: BridgeToHubMessage[] = [];
    const server = net.createServer((socket) => {
        connections.push(socket);
        socket.setEncoding("utf8");
        let buf = "";
        socket.on("data", (chunk: string | Buffer) => {
            buf += typeof chunk === "string" ? chunk : chunk.toString("utf8");
            let nl = buf.indexOf("\n");
            while (nl >= 0) {
                const line = buf.slice(0, nl).trim();
                buf = buf.slice(nl + 1);
                nl = buf.indexOf("\n");
                if (line.length === 0) continue;
                try {
                    inbound.push(JSON.parse(line) as BridgeToHubMessage);
                } catch {
                    /* ignore */
                }
            }
        });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const port = (server.address() as net.AddressInfo).port;
    return {
        port,
        server,
        connections,
        inbound,
        close: async () => {
            for (const s of connections) s.destroy();
            await new Promise<void>((resolve) => server.close(() => resolve()));
        },
    };
}

function waitFor<T>(probe: () => T | undefined, timeoutMs = 1_000): Promise<T> {
    return new Promise((resolve, reject) => {
        const start = Date.now();
        const tick = () => {
            const v = probe();
            if (v !== undefined) return resolve(v);
            if (Date.now() - start > timeoutMs) return reject(new Error("waitFor timed out"));
            setTimeout(tick, 5);
        };
        tick();
    });
}

describe("HubClient NDJSON framing", () => {
    let raw: RawHub;
    beforeEach(async () => { raw = await startRawHub(); });
    afterEach(async () => { await raw.close(); });

    it("multi-frame in one chunk + blank lines skipped + CRLF handled", async () => {
        const sends: SendMessage[] = [];
        const client = new HubClient({
            host: "127.0.0.1",
            port: raw.port,
            sessionId: "sess",
            logger: silent,
            callbacks: { onSend: (m) => sends.push(m), onPermissionVerdict: () => undefined, onClose: () => undefined },
        });
        await client.connect();
        await waitFor(() => (raw.connections[0] ? raw.connections[0] : undefined));
        const hubSocket = raw.connections[0]!;

        // ack + 2 件の send を 1 chunk で送る + 空行 + CRLF 混在
        const ack = JSON.stringify({ type: "ack_register" });
        const s1 = JSON.stringify({ type: "send", chat_id: "c1", text: "a" });
        const s2 = JSON.stringify({ type: "send", chat_id: "c2", text: "b" });
        hubSocket.write(ack + "\n\n" + s1 + "\r\n" + s2 + "\n");

        await waitFor(() => (sends.length >= 2 ? true : undefined));
        expect(sends.map((s) => s.chat_id)).toEqual(["c1", "c2"]);
        client.close();
    });

    it("broken JSON in middle of stream → parse_error log, subsequent frames still processed", async () => {
        const sends: SendMessage[] = [];
        const lines: string[] = [];
        const recordingLogger = new StructuredLog("test", "DEBUG", (l) => lines.push(l));
        const client = new HubClient({
            host: "127.0.0.1",
            port: raw.port,
            sessionId: "sess",
            logger: recordingLogger,
            callbacks: { onSend: (m) => sends.push(m), onPermissionVerdict: () => undefined, onClose: () => undefined },
        });
        await client.connect();
        await waitFor(() => (raw.connections[0] ? raw.connections[0] : undefined));
        const hubSocket = raw.connections[0]!;

        hubSocket.write(JSON.stringify({ type: "ack_register" }) + "\n");
        hubSocket.write("{not json}\n");
        hubSocket.write(JSON.stringify({ type: "send", chat_id: "after", text: "ok" }) + "\n");

        await waitFor(() => (sends.length >= 1 ? true : undefined));
        expect(sends[0]!.chat_id).toBe("after");
        expect(lines.some((l) => l.includes("event=parse_error"))).toBe(true);
        client.close();
    });

    it("frame split across two chunks (mid-JSON) is reassembled", async () => {
        const verdicts: PermissionVerdictMessage[] = [];
        const client = new HubClient({
            host: "127.0.0.1",
            port: raw.port,
            sessionId: "sess",
            logger: silent,
            callbacks: { onSend: () => undefined, onPermissionVerdict: (m) => verdicts.push(m), onClose: () => undefined },
        });
        await client.connect();
        await waitFor(() => (raw.connections[0] ? raw.connections[0] : undefined));
        const hubSocket = raw.connections[0]!;

        hubSocket.write(JSON.stringify({ type: "ack_register" }) + "\n");
        const verdict = JSON.stringify({ type: "permission_verdict", request_id: "r1", behavior: "allow" });
        const half = Math.floor(verdict.length / 2);
        hubSocket.write(verdict.slice(0, half));
        // wait a tick to ensure the partial chunk lands
        await new Promise((r) => setTimeout(r, 20));
        hubSocket.write(verdict.slice(half) + "\n");

        await waitFor(() => (verdicts.length >= 1 ? true : undefined));
        expect(verdicts[0]!.request_id).toBe("r1");
        client.close();
    });
});

describe("HubClient pre-ack queue FIFO (P1-1)", () => {
    let raw: RawHub;
    beforeEach(async () => { raw = await startRawHub(); });
    afterEach(async () => { await raw.close(); });

    it("queues pre-ack messages and flushes them in insertion order", async () => {
        const client = new HubClient({
            host: "127.0.0.1",
            port: raw.port,
            sessionId: "sess",
            logger: silent,
            callbacks: { onSend: () => undefined, onPermissionVerdict: () => undefined, onClose: () => undefined },
        });
        await client.connect();
        // ack をまだ送らない: pre-ack queue に積む
        client.sendReply("c1", "first");
        client.sendPermission({
            request_id: "r1", session_id: "sess",
            tool_name: "Bash", description: "x", input_preview: "x",
        });
        client.sendPermissionAbort("r1", "ignore");
        client.sendReply("c2", "last");
        expect(client.queueSize()).toBe(4);

        await waitFor(() => (raw.connections[0] ? raw.connections[0] : undefined));
        const hubSocket = raw.connections[0]!;
        // register が先頭に来る (connect の中で書かれている)
        await waitFor(() => (raw.inbound.some((m) => m.type === "register") ? true : undefined));

        // ack を送って queue flush をトリガ
        hubSocket.write(JSON.stringify({ type: "ack_register" }) + "\n");
        await waitFor(() => (client.isRegistered() ? true : undefined));
        await waitFor(() => (raw.inbound.length >= 5 ? true : undefined));

        // inbound 順序: register, reply(c1), permission(r1), permission_abort(r1), reply(c2)
        const types = raw.inbound.map((m) => m.type);
        expect(types).toEqual(["register", "reply", "permission", "permission_abort", "reply"]);
        const second = raw.inbound[1] as { chat_id: string };
        const fifth = raw.inbound[4] as { chat_id: string };
        expect(second.chat_id).toBe("c1");
        expect(fifth.chat_id).toBe("c2");
        client.close();
    });
});
