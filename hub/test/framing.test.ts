// BridgeServer の NDJSON フレーミング境界条件と register 前ガードの unit test。
// 実 socket は通すと flush タイミング依存になるので、最小限の FakeSocket で onData を直接駆動する。

import { EventEmitter } from "node:events";
import type { Socket } from "node:net";
import { describe, expect, it } from "vitest";
import { BridgeServer } from "../src/server/BridgeServer.js";
import { OutstandingPermissions } from "../src/state/OutstandingPermissions.js";
import { SessionRegistry } from "../src/state/SessionRegistry.js";
import { StructuredLog } from "../src/log/StructuredLog.js";
import type { PhoneSseEvent } from "../src/wire/PhoneWire.js";

// EventEmitter ベースの最小 Socket もどき。BridgeServer は data/close/error event と
// `setEncoding`, `destroy`, `write`, `destroyed`, `writableEnded` だけを使う。
class FakeSocket extends EventEmitter {
    destroyed = false;
    writableEnded = false;
    writes: string[] = [];
    setEncoding(_enc: string): this { return this; }
    write(line: string): boolean {
        this.writes.push(line);
        return true;
    }
    destroy(): void {
        this.destroyed = true;
        this.emit("close");
    }
    asNetSocket(): Socket {
        return this as unknown as Socket;
    }
    feed(chunk: string): void {
        this.emit("data", chunk);
    }
}

function buildHarness() {
    const sessions = new SessionRegistry();
    const outstanding = new OutstandingPermissions();
    const lines: string[] = [];
    const logger = new StructuredLog("test", "DEBUG", (l) => lines.push(l));
    const broadcasts: PhoneSseEvent[] = [];
    const bridge = new BridgeServer({
        sessions,
        outstanding,
        phoneBroadcast: (e) => broadcasts.push(e),
        logger,
        now: () => 1_000,
    });
    return { sessions, outstanding, logger, lines, bridge, broadcasts };
}

/** BridgeServer の private な onConnection を経由するため、内部の `server.emit("connection", socket)` を使う。 */
function attachSocket(bridge: BridgeServer, socket: FakeSocket): void {
    // BridgeServer のコンストラクタで `createServer((socket) => this.onConnection(socket))`。
    // emit("connection") で同じ callback を発火させる。
    const server = (bridge as unknown as { server: EventEmitter }).server;
    server.emit("connection", socket.asNetSocket());
}

describe("BridgeServer NDJSON framing", () => {
    it("complete frame in single chunk", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        s.feed(JSON.stringify({ type: "register", session_id: "A", pid: 1 }) + "\n");
        expect(h.sessions.get("A")).toBeDefined();
        expect(s.writes[0]).toContain('"type":"ack_register"');
    });

    it("two frames concatenated in one chunk", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        const reg = JSON.stringify({ type: "register", session_id: "A", pid: 1 });
        const perm = JSON.stringify({
            type: "permission", request_id: "r1", session_id: "A",
            tool_name: "Bash", description: "x", input_preview: "x",
        });
        s.feed(reg + "\n" + perm + "\n");
        expect(h.outstanding.has("r1")).toBe(true);
        expect(h.broadcasts.some((e) => e.type === "permission")).toBe(true);
    });

    it("frame split across two chunks (mid-JSON)", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        const raw = JSON.stringify({ type: "register", session_id: "A", pid: 1 });
        const half = Math.floor(raw.length / 2);
        s.feed(raw.slice(0, half));
        expect(h.sessions.size()).toBe(0); // not parsed yet
        s.feed(raw.slice(half) + "\n");
        expect(h.sessions.get("A")).toBeDefined();
    });

    it("blank lines (\\n\\n) are skipped", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        const reg = JSON.stringify({ type: "register", session_id: "A", pid: 1 });
        s.feed("\n\n" + reg + "\n\n");
        expect(h.sessions.get("A")).toBeDefined();
    });

    it("CRLF line endings (\\r\\n) are handled (trim)", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        const reg = JSON.stringify({ type: "register", session_id: "A", pid: 1 });
        s.feed(reg + "\r\n");
        expect(h.sessions.get("A")).toBeDefined();
    });

    it("malformed JSON is logged as parse_error but doesn't crash", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        s.feed("{not json}\n");
        expect(h.lines.some((l) => l.includes("event=parse_error"))).toBe(true);
        // 後続フレームは普通に処理される
        s.feed(JSON.stringify({ type: "register", session_id: "A", pid: 1 }) + "\n");
        expect(h.sessions.get("A")).toBeDefined();
    });
});

describe("BridgeServer register-before-message guard", () => {
    function setup(): { h: ReturnType<typeof buildHarness>; s: FakeSocket } {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        return { h, s };
    }

    it("reply before register → reject + log warn, no broadcast", () => {
        const { h, s } = setup();
        s.feed(JSON.stringify({
            type: "reply", chat_id: "c1", session_id: "X", text: "boom",
        }) + "\n");
        expect(h.broadcasts).toEqual([]);
        expect(h.lines.some((l) => l.includes("event=msg_before_register") && l.includes("type=reply"))).toBe(true);
    });

    it("permission before register → reject", () => {
        const { h, s } = setup();
        s.feed(JSON.stringify({
            type: "permission", request_id: "r1", session_id: "X",
            tool_name: "Bash", description: "x", input_preview: "x",
        }) + "\n");
        expect(h.broadcasts).toEqual([]);
        expect(h.outstanding.size()).toBe(0);
        expect(h.lines.some((l) => l.includes("event=msg_before_register") && l.includes("type=permission"))).toBe(true);
    });

    it("permission_abort before register → reject (no broadcast of unknown abort)", () => {
        const { h, s } = setup();
        s.feed(JSON.stringify({ type: "permission_abort", request_id: "r1" }) + "\n");
        expect(h.broadcasts).toEqual([]);
        expect(h.lines.some((l) => l.includes("event=msg_before_register") && l.includes("type=permission_abort"))).toBe(true);
    });

    it("permission_abort for unknown request_id (after register) → no broadcast", () => {
        const { h, s } = setup();
        s.feed(JSON.stringify({ type: "register", session_id: "A", pid: 1 }) + "\n");
        h.broadcasts.length = 0; // clear session_active
        s.feed(JSON.stringify({ type: "permission_abort", request_id: "unknown" }) + "\n");
        expect(h.broadcasts).toEqual([]);
        expect(h.lines.some((l) => l.includes("event=abort_unknown_request"))).toBe(true);
    });
});

describe("BridgeServer session_id mismatch", () => {
    it("permission with msg.session_id ≠ state.sessionId → reject", () => {
        const h = buildHarness();
        const s = new FakeSocket();
        attachSocket(h.bridge, s);
        s.feed(JSON.stringify({ type: "register", session_id: "A", pid: 1 }) + "\n");
        h.broadcasts.length = 0;
        s.feed(JSON.stringify({
            type: "permission", request_id: "r1", session_id: "B-WRONG",
            tool_name: "Bash", description: "x", input_preview: "x",
        }) + "\n");
        expect(h.broadcasts).toEqual([]);
        expect(h.outstanding.size()).toBe(0);
        expect(h.lines.some((l) => l.includes("event=session_id_mismatch"))).toBe(true);
    });
});
