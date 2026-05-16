// McpServer の単体テスト (InMemoryTransport で stdio をハーネス)。
// - reply tool happy path / unknown tool 拒否 / hub disconnected で isError
// - permission_request notification → hub.sendPermission に届く
// - deliverSend with image → meta.image_path がセットされる notification

import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { beforeEach, afterEach, describe, expect, it } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { z } from "zod";
import { McpServer } from "../src/McpServer.js";
import { ImageStaging } from "../src/ImageStaging.js";
import { StructuredLog } from "../src/log/StructuredLog.js";
import type { PermissionMessage, PermissionAbortMessage, ReplyMessage } from "../src/wire/HubWire.js";

const silent = new StructuredLog("test", "ERROR", () => undefined);

// HubClient の I/F だけ満たす最小 stub。connect/close は要らない。
class FakeHubClient {
    replies: { chatId: string; text: string }[] = [];
    permissions: Omit<PermissionMessage, "type">[] = [];
    aborts: { requestId: string; reason?: string | undefined }[] = [];
    sendReplyReturns = true;
    sendReply(chatId: string, text: string): boolean {
        if (!this.sendReplyReturns) return false;
        this.replies.push({ chatId, text });
        return true;
    }
    sendPermission(msg: Omit<PermissionMessage, "type">): boolean {
        this.permissions.push(msg);
        return true;
    }
    sendPermissionAbort(requestId: string, reason?: string): boolean {
        this.aborts.push({ requestId, reason });
        return true;
    }
}

interface Harness {
    server: McpServer;
    client: Client;
    fakeHub: FakeHubClient;
    images: ImageStaging;
    notifications: { method: string; params: unknown }[];
    close(): Promise<void>;
}

async function startHarness(): Promise<Harness> {
    const [serverTransport, clientTransport] = InMemoryTransport.createLinkedPair();

    const fakeHub = new FakeHubClient();
    const inboxDir = await mkdtemp(join(tmpdir(), "bridge-mcp-test-"));
    const images = new ImageStaging(inboxDir, silent);

    const server = new McpServer({
        sessionId: "sess-test",
        // FakeHubClient は HubClient と shape は違うが、McpServer が呼ぶ method だけ持っていれば良い。
        hub: fakeHub as never,
        images,
        logger: silent,
        onClose: () => undefined,
        transport: serverTransport,
    });

    const client = new Client(
        { name: "test-client", version: "0.0.0" },
        {
            capabilities: {
                experimental: {
                    "claude/channel": {},
                    "claude/channel/permission": {},
                },
            },
        },
    );

    // server.start() は connect が完了するまで待つので、その間に client も connect する。
    const notifications: { method: string; params: unknown }[] = [];
    // 全 notification を broad に観測したいので fallback handler を入れる。
    client.fallbackNotificationHandler = async (notification) => {
        notifications.push({ method: notification.method, params: notification.params });
    };

    await Promise.all([server.start(), client.connect(clientTransport)]);

    return {
        server,
        client,
        fakeHub,
        images,
        notifications,
        close: async () => {
            server.close();
            await client.close();
            await images.cleanup();
        },
    };
}

describe("McpServer (in-memory transport)", () => {
    let h: Harness;
    beforeEach(async () => { h = await startHarness(); });
    afterEach(async () => { await h.close(); });

    it("listTools returns the reply tool", async () => {
        const result = await h.client.listTools();
        expect(result.tools).toHaveLength(1);
        expect(result.tools[0]!.name).toBe("reply");
    });

    it("reply tool happy path → hub.sendReply", async () => {
        const result = await h.client.callTool({
            name: "reply",
            arguments: { chat_id: "c-1", text: "hi" },
        });
        expect(result.isError).toBeFalsy();
        expect(h.fakeHub.replies).toEqual([{ chatId: "c-1", text: "hi" }]);
    });

    it("reply tool with hub disconnected → isError + drop", async () => {
        h.fakeHub.sendReplyReturns = false;
        const result = await h.client.callTool({
            name: "reply",
            arguments: { chat_id: "c-1", text: "bye" },
        });
        expect(result.isError).toBe(true);
        expect(h.fakeHub.replies).toEqual([]);
    });

    it("unknown tool name is rejected with error", async () => {
        await expect(
            h.client.callTool({ name: "nosuch", arguments: {} }),
        ).rejects.toThrow(/unknown tool/);
    });

    it("permission_request notification → hub.sendPermission", async () => {
        await h.client.notification({
            method: "notifications/claude/channel/permission_request",
            params: {
                request_id: "req-1",
                tool_name: "Bash",
                description: "ls",
                input_preview: "ls",
            },
        });
        // notification handler が走るまで微小待ち
        await new Promise((r) => setTimeout(r, 30));
        expect(h.fakeHub.permissions).toHaveLength(1);
        expect(h.fakeHub.permissions[0]).toMatchObject({
            request_id: "req-1",
            session_id: "sess-test",
            tool_name: "Bash",
        });
    });

    it("permission_abort notification → hub.sendPermissionAbort", async () => {
        await h.client.notification({
            method: "notifications/claude/channel/permission_abort",
            params: { request_id: "req-2", reason: "user_cancel" },
        });
        await new Promise((r) => setTimeout(r, 30));
        expect(h.fakeHub.aborts).toHaveLength(1);
        expect(h.fakeHub.aborts[0]!.requestId).toBe("req-2");
        expect(h.fakeHub.aborts[0]!.reason).toBe("user_cancel");
    });

    it("deliverSend without image emits channel notification (no image_path)", async () => {
        h.server.deliverSend({ type: "send", chat_id: "c-9", text: "hello" });
        await waitForNotification(h.notifications, "notifications/claude/channel");
        const last = h.notifications.find((n) => n.method === "notifications/claude/channel")!;
        const params = last.params as { content: string; meta: Record<string, string> };
        expect(params.content).toBe("hello");
        expect(params.meta.chat_id).toBe("c-9");
        expect(params.meta.image_path).toBeUndefined();
    });

    it("deliverSend with image stages base64 → meta.image_path points to file", async () => {
        const jpegBase64 = Buffer.from([0xff, 0xd8, 0xff]).toString("base64");
        h.server.deliverSend({
            type: "send",
            chat_id: "c-img",
            text: "",
            image_base64: jpegBase64,
            image_mime: "image/jpeg",
        });
        await waitForNotification(h.notifications, "notifications/claude/channel");
        const evt = h.notifications.find(
            (n) =>
                n.method === "notifications/claude/channel" &&
                (n.params as { meta: Record<string, string> }).meta.chat_id === "c-img",
        )!;
        const params = evt.params as { content: string; meta: Record<string, string> };
        expect(params.meta.image_path).toMatch(/\.jpg$/);
        expect(params.content).toBe("(image)"); // text empty なら fallback
        const bytes = await readFile(params.meta.image_path!);
        expect(bytes[0]).toBe(0xff);
    });

    it("deliverPermissionVerdict emits permission notification", async () => {
        h.server.deliverPermissionVerdict({
            type: "permission_verdict",
            request_id: "req-9",
            behavior: "allow",
        });
        await waitForNotification(h.notifications, "notifications/claude/channel/permission");
        const evt = h.notifications.find(
            (n) => n.method === "notifications/claude/channel/permission",
        )!;
        const params = evt.params as { request_id: string; behavior: string };
        expect(params.request_id).toBe("req-9");
        expect(params.behavior).toBe("allow");
    });
});

async function waitForNotification(
    arr: { method: string; params: unknown }[],
    method: string,
    timeoutMs = 1_000,
): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
        if (arr.some((n) => n.method === method)) return;
        await new Promise((r) => setTimeout(r, 10));
    }
    throw new Error(`timed out waiting for notification ${method}; got: ${JSON.stringify(arr.map((n) => n.method))}`);
}
