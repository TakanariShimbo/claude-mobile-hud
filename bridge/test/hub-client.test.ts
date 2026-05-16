// HubClient の単体 + Hub と組んだ integration テスト。
// Hub は別 npm package なので、relative path で参照して直接 import する。

import net from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { BridgeServer } from "../../hub/src/server/BridgeServer.js";
import { OutstandingPermissions } from "../../hub/src/state/OutstandingPermissions.js";
import { SessionRegistry } from "../../hub/src/state/SessionRegistry.js";
import { StructuredLog as HubLog } from "../../hub/src/log/StructuredLog.js";
import type { PhoneSseEvent } from "../../hub/src/wire/PhoneWire.js";
import { HubClient } from "../src/HubClient.js";
import { StructuredLog } from "../src/log/StructuredLog.js";
import type { SendMessage, PermissionVerdictMessage } from "../src/wire/HubWire.js";

const silentSink = () => undefined;
const silent = new StructuredLog("test", "ERROR", silentSink);
const hubSilent = new HubLog("test-hub", "ERROR", silentSink);

async function pickPort(): Promise<number> {
    return new Promise((resolve) => {
        const srv = net.createServer();
        srv.unref();
        srv.listen(0, "127.0.0.1", () => {
            const port = (srv.address() as net.AddressInfo).port;
            srv.close(() => resolve(port));
        });
    });
}

interface HubHarness {
    bridge: BridgeServer;
    port: number;
    sessions: SessionRegistry;
    outstanding: OutstandingPermissions;
    phoneEvents: PhoneSseEvent[];
    close(): Promise<void>;
}

async function startHubBridgeOnly(): Promise<HubHarness> {
    const sessions = new SessionRegistry();
    const outstanding = new OutstandingPermissions();
    const phoneEvents: PhoneSseEvent[] = [];
    const bridge = new BridgeServer({
        sessions,
        outstanding,
        phoneBroadcast: (e) => phoneEvents.push(e),
        logger: hubSilent,
    });
    const port = await pickPort();
    await bridge.listen(port, "127.0.0.1");
    return {
        bridge,
        port,
        sessions,
        outstanding,
        phoneEvents,
        close: async () => {
            await bridge.close();
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

describe("HubClient ↔ Hub.BridgeServer integration", () => {
    let h: HubHarness;

    beforeEach(async () => { h = await startHubBridgeOnly(); });
    afterEach(async () => { await h.close(); });

    it("register → ack_register → registered=true", async () => {
        const client = new HubClient({
            host: "127.0.0.1",
            port: h.port,
            sessionId: "sess-A",
            logger: silent,
            callbacks: { onSend: () => undefined, onPermissionVerdict: () => undefined, onClose: () => undefined },
        });
        await client.connect();
        await waitFor(() => (client.isRegistered() ? true : undefined));
        expect(h.sessions.get("sess-A")?.pid).toBe(process.pid);
        client.close();
    });

    it("pre-ack sendReply / sendPermission are queued and flushed after ack", async () => {
        const client = new HubClient({
            host: "127.0.0.1",
            port: h.port,
            sessionId: "sess-B",
            logger: silent,
            callbacks: { onSend: () => undefined, onPermissionVerdict: () => undefined, onClose: () => undefined },
        });
        await client.connect();
        // ack を待たずに送信 → queue へ
        client.sendReply("chat-1", "sess-B", "hello");
        client.sendPermission({
            request_id: "req-1",
            session_id: "sess-B",
            tool_name: "Bash",
            description: "ls",
            input_preview: "ls",
        });
        expect(client.queueSize()).toBeGreaterThan(0);

        await waitFor(() => (client.isRegistered() ? true : undefined));
        // ack 後に queue が flush され、Hub の outstanding に積まれる
        await waitFor(() => (h.outstanding.has("req-1") ? true : undefined));
        // Phone broadcast には reply と permission が乗っている
        await waitFor(() => (h.phoneEvents.some((e) => e.type === "reply") ? true : undefined));
        await waitFor(() => (h.phoneEvents.some((e) => e.type === "permission") ? true : undefined));
        client.close();
    });

    it("Hub → Bridge: send / permission_verdict が callback で受信できる", async () => {
        const sends: SendMessage[] = [];
        const verdicts: PermissionVerdictMessage[] = [];
        const client = new HubClient({
            host: "127.0.0.1",
            port: h.port,
            sessionId: "sess-C",
            logger: silent,
            callbacks: {
                onSend: (m) => sends.push(m),
                onPermissionVerdict: (m) => verdicts.push(m),
                onClose: () => undefined,
            },
        });
        await client.connect();
        await waitFor(() => (client.isRegistered() ? true : undefined));

        // Hub 側から send を発火 (本来は HttpServer 経由だがここでは BridgeServer 直叩き)
        h.bridge.sendToSession("sess-C", {
            type: "send",
            chat_id: "chat-1",
            text: "hi from claude",
        });
        await waitFor(() => (sends.length > 0 ? true : undefined));
        expect(sends[0]!.text).toBe("hi from claude");

        // permission を Bridge から登録 (Hub の outstanding に積む) → verdict を Hub 側で発火
        client.sendPermission({
            request_id: "req-2",
            session_id: "sess-C",
            tool_name: "Bash",
            description: "x",
            input_preview: "x",
        });
        await waitFor(() => (h.outstanding.has("req-2") ? true : undefined));
        h.bridge.sendToSession("sess-C", {
            type: "permission_verdict",
            request_id: "req-2",
            behavior: "allow",
        });
        await waitFor(() => (verdicts.length > 0 ? true : undefined));
        expect(verdicts[0]!.behavior).toBe("allow");
        client.close();
    });

    it("image_base64 を Bridge へ往復させる (Hub→Bridge の send フィールド名 parity)", async () => {
        const sends: SendMessage[] = [];
        const client = new HubClient({
            host: "127.0.0.1",
            port: h.port,
            sessionId: "sess-D",
            logger: silent,
            callbacks: {
                onSend: (m) => sends.push(m),
                onPermissionVerdict: () => undefined,
                onClose: () => undefined,
            },
        });
        await client.connect();
        await waitFor(() => (client.isRegistered() ? true : undefined));

        h.bridge.sendToSession("sess-D", {
            type: "send",
            chat_id: "chat-2",
            text: "",
            image_base64: "AAAA",
            image_mime: "image/png",
        });
        await waitFor(() => (sends.length > 0 ? true : undefined));
        expect(sends[0]!.image_base64).toBe("AAAA");
        expect(sends[0]!.image_mime).toBe("image/png");
        client.close();
    });

    it("Hub が close すると onClose(remote) が発火", async () => {
        let closedReason: "remote" | "local" | null = null;
        const client = new HubClient({
            host: "127.0.0.1",
            port: h.port,
            sessionId: "sess-E",
            logger: silent,
            callbacks: {
                onSend: () => undefined,
                onPermissionVerdict: () => undefined,
                onClose: (r) => { closedReason = r; },
            },
        });
        await client.connect();
        await waitFor(() => (client.isRegistered() ? true : undefined));

        await h.bridge.close();
        await waitFor(() => (closedReason !== null ? closedReason : undefined));
        expect(closedReason).toBe("remote");
        client.close();
    });
});
