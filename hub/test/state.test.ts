import { describe, expect, it } from "vitest";
import { OutstandingPermissions, type OutstandingEntry } from "../src/state/OutstandingPermissions.js";
import { SessionRegistry } from "../src/state/SessionRegistry.js";
import { ChatRegistry } from "../src/state/ChatRegistry.js";
import type { Socket } from "node:net";

function entry(overrides: Partial<OutstandingEntry> = {}): OutstandingEntry {
    return {
        requestId: "req-1",
        sessionId: "sess-1",
        toolName: "Bash",
        description: "desc",
        inputPreview: "ls",
        createdAtMs: 1_000,
        bridgeSessionId: "bridge-A",
        ...overrides,
    };
}

describe("OutstandingPermissions", () => {
    it("add / has / remove", () => {
        const out = new OutstandingPermissions();
        out.add(entry({ requestId: "r1" }));
        expect(out.has("r1")).toBe(true);
        expect(out.remove("r1")?.requestId).toBe("r1");
        expect(out.has("r1")).toBe(false);
        expect(out.remove("r1")).toBeUndefined();
    });

    it("onBridgeDisconnected aborts only matching entries (FR-HU-13)", () => {
        const out = new OutstandingPermissions();
        out.add(entry({ requestId: "r1", bridgeSessionId: "A" }));
        out.add(entry({ requestId: "r2", bridgeSessionId: "B" }));
        out.add(entry({ requestId: "r3", bridgeSessionId: "A" }));

        const pushed: string[] = [];
        const removed = out.onBridgeDisconnected("A", (event) => pushed.push(event.request_id));

        expect(removed.sort()).toEqual(["r1", "r3"]);
        expect(pushed.sort()).toEqual(["r1", "r3"]);
        expect(out.size()).toBe(1);
        expect(out.has("r2")).toBe(true);
    });

    it("buildSnapshot orders by createdAtMs ascending (AD-13)", () => {
        const out = new OutstandingPermissions();
        out.add(entry({ requestId: "old", createdAtMs: 100 }));
        out.add(entry({ requestId: "new", createdAtMs: 300 }));
        out.add(entry({ requestId: "mid", createdAtMs: 200 }));

        const snap = out.buildSnapshot();
        expect(snap.requestIds).toEqual(["old", "mid", "new"]);
        expect(snap.entries.map((e) => e.requestId)).toEqual(["old", "mid", "new"]);
    });

    it("toSse drops null sessionId", () => {
        const sse = OutstandingPermissions.toSse(entry({ sessionId: null }));
        expect(sse.session_id).toBeUndefined();
    });
});

describe("SessionRegistry", () => {
    const fakeSocket = (): Socket => ({}) as Socket;

    it("register / get / activeIds / unregister", () => {
        const reg = new SessionRegistry();
        reg.register({
            sessionId: "s1",
            bridgeSessionId: "b1",
            pid: 100,
            socket: fakeSocket(),
            registeredAtMs: 0,
        });
        expect(reg.get("s1")?.pid).toBe(100);
        expect(reg.activeIds()).toEqual(["s1"]);
        expect(reg.unregister("s1")?.sessionId).toBe("s1");
        expect(reg.size()).toBe(0);
    });

    it("unregisterByBridgeSessionId removes all matching", () => {
        const reg = new SessionRegistry();
        reg.register({ sessionId: "s1", bridgeSessionId: "A", pid: 1, socket: fakeSocket(), registeredAtMs: 0 });
        reg.register({ sessionId: "s2", bridgeSessionId: "B", pid: 2, socket: fakeSocket(), registeredAtMs: 0 });
        reg.register({ sessionId: "s3", bridgeSessionId: "A", pid: 3, socket: fakeSocket(), registeredAtMs: 0 });
        const removed = reg.unregisterByBridgeSessionId("A");
        expect(removed.map((e) => e.sessionId).sort()).toEqual(["s1", "s3"]);
        expect(reg.activeIds()).toEqual(["s2"]);
    });
});

describe("ChatRegistry", () => {
    const UUID_V4_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

    it("mint produces RFC 4122 UUID v4 (Phase 2 §4.6)", () => {
        const reg = new ChatRegistry();
        const a = reg.mint();
        const b = reg.mint();
        expect(a).toMatch(UUID_V4_RE);
        expect(b).toMatch(UUID_V4_RE);
        expect(a).not.toBe(b);
    });
});
