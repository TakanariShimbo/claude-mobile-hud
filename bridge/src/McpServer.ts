// Claude Code 側との stdio MCP server。Phase 3 §6.2.1。
//
// - tool: `reply(chat_id, text)` → Claude が呼ぶ → Hub に reply 転送
// - notification 受信: `notifications/claude/channel/permission_request` → Hub に permission 転送
// - notification 送出:
//     - `notifications/claude/channel` → Claude へ channel message (Hub の send を Bridge が staging 後)
//     - `notifications/claude/channel/permission` → Claude へ verdict (Hub からの permission_verdict)
//
// 重要: stdout は MCP プロトコル専用。console.log は絶対に使わない (StructuredLog は stderr)。

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import type { Transport } from "@modelcontextprotocol/sdk/shared/transport.js";
import {
    CallToolRequestSchema,
    ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import { readFileSync } from "node:fs";
import type { HubClient } from "./HubClient.js";
import { ImageStaging } from "./ImageStaging.js";
import type { SendMessage, PermissionVerdictMessage } from "./wire/HubWire.js";
import type { Logger } from "./log/StructuredLog.js";

const SERVER_NAME = "claude-mobile-hud-bridge";
// package.json から version を読む (二重管理回避、P3-1)。
const SERVER_VERSION: string = ((): string => {
    try {
        const pkgUrl = new URL("../package.json", import.meta.url);
        const raw = readFileSync(pkgUrl, "utf8");
        return (JSON.parse(raw) as { version: string }).version;
    } catch {
        return "0.0.0";
    }
})();

const INSTRUCTIONS = [
    "Messages from a mobile client arrive as `notifications/claude/channel`",
    "with `meta.chat_id` (always present) and `meta.image_path` (when the user attached an image — read it with the Read tool if you need to inspect it).",
    "",
    "RULES:",
    "- To respond, ALWAYS use the `reply` tool. Pass `chat_id` verbatim from the inbound meta; do NOT invent or modify it.",
    "- Treat each `chat_id` as one independent conversation thread.",
    "- Do NOT emit `notifications/claude/channel*` yourself — those are server→client only. The `reply` tool is the sole client→server outbound path.",
    "- If a permission notification (`notifications/claude/channel/permission`) arrives, it carries the user verdict for a tool call you previously initiated; proceed accordingly.",
].join("\n");

const REPLY_TOOL = {
    name: "reply",
    description: "Send a message back to the mobile client through this channel",
    inputSchema: {
        type: "object" as const,
        required: ["chat_id", "text"],
        properties: {
            chat_id: {
                type: "string" as const,
                description: "chat_id from the inbound notification meta",
            },
            text: { type: "string" as const, description: "Reply text" },
        },
    },
};

const ReplyArgs = z.object({
    chat_id: z.string().min(1),
    text: z.string(),
});

const PermissionRequestNotification = z.object({
    method: z.literal("notifications/claude/channel/permission_request"),
    params: z.object({
        request_id: z.string(),
        tool_name: z.string(),
        description: z.string(),
        input_preview: z.string(),
    }),
});

const PermissionAbortNotification = z.object({
    method: z.literal("notifications/claude/channel/permission_abort"),
    params: z.object({
        request_id: z.string(),
        reason: z.string().optional(),
    }),
});

export interface McpServerOptions {
    sessionId: string;
    hub: HubClient;
    images: ImageStaging;
    logger: Logger;
    onClose: () => void;
    /**
     * テスト時に InMemoryTransport などを差し込むための seam (省略時は StdioServerTransport)。
     */
    transport?: Transport;
}

export class McpServer {
    private readonly server: Server;
    /**
     * `notifications/claude/channel` (= deliverSend 経路) を全 chat 横断で直列化する Promise chain。
     * image_base64 の staging が async なため、ある send の image 保存中に次の send が
     * notification を先に届けないよう順序を担保する。同 chat 内ではなく全 channel に対する直列化。
     *
     * `deliverPermissionVerdict` (= notifications/claude/channel/permission) はこの queue を
     * 通さない: permission は channel とは独立した notification 経路で、互いの順序を縛る
     * 必要が無いため。
     *
     * **メモリ安全性**: `deliverSend` 毎に `this.writeQueue = this.writeQueue.then(...)` で
     * 置換するが、解決済み Promise の `then` callback は microtask 完了後に GC 対象になる。
     * Promise chain が「N 回呼ばれたら深さ N の参照グラフが残る」わけではない (V8 は
     * 解決済み prior Promise の参照を保持しない) ので、長時間動かしてもメモリは伸びない。
     */
    private writeQueue: Promise<void> = Promise.resolve();

    constructor(private readonly opts: McpServerOptions) {
        this.server = new Server(
            { name: SERVER_NAME, version: SERVER_VERSION },
            {
                capabilities: {
                    // `experimental.claude/channel*` は MCP 公式仕様には無い custom capability。
                    // Claude Code 側がこの 2 つを discover した場合に、対応する
                    // `notifications/claude/channel{,/permission,/permission_request,/permission_abort}`
                    // method を listen / emit してくれる前提。POC v1 から踏襲。
                    experimental: {
                        "claude/channel": {},
                        "claude/channel/permission": {},
                    },
                    tools: {},
                },
                instructions: INSTRUCTIONS,
            },
        );
        this.wireHandlers();
    }

    async start(): Promise<void> {
        // onclose は connect() の前に設定する。await 中に transport が close を発火しても
        // 取りこぼさないようにするため (P1-2)。
        this.server.onclose = () => {
            this.opts.logger.info("mcp_closed");
            this.opts.onClose();
        };
        const transport = this.opts.transport ?? new StdioServerTransport();
        await this.server.connect(transport);
        this.opts.logger.info("mcp_connected");
    }

    close(): void {
        try {
            this.server.close();
        } catch {
            /* ignore */
        }
    }

    /** Hub の SendMessage を Claude へ通知。画像があれば staging してから。 */
    deliverSend(msg: SendMessage): void {
        this.writeQueue = this.writeQueue.then(async () => {
            const meta: Record<string, string> = {
                chat_id: msg.chat_id,
                session_id: this.opts.sessionId,
            };
            let content = msg.text;
            if (msg.image_base64 && msg.image_mime) {
                try {
                    meta.image_path = await this.opts.images.save(msg.image_base64, msg.image_mime);
                    if (content.length === 0) content = "(image)";
                } catch (err) {
                    this.opts.logger.warn("image_save_failed", {
                        chat_id: msg.chat_id,
                        mime: msg.image_mime,
                        error: (err as Error).message,
                    });
                }
            }
            await this.notify("notifications/claude/channel", { content, meta });
        });
    }

    deliverPermissionVerdict(msg: PermissionVerdictMessage): void {
        void this.notify("notifications/claude/channel/permission", {
            request_id: msg.request_id,
            behavior: msg.behavior,
        });
    }

    private wireHandlers(): void {
        this.server.setRequestHandler(ListToolsRequestSchema, async () => ({
            tools: [REPLY_TOOL],
        }));

        this.server.setRequestHandler(CallToolRequestSchema, async (req) => {
            if (req.params.name !== "reply") {
                throw new Error(`unknown tool: ${req.params.name}`);
            }
            const args = ReplyArgs.parse(req.params.arguments);
            const ok = this.opts.hub.sendReply(args.chat_id, args.text);
            if (!ok) {
                // Hub と切れている時に成功を返すと Claude 側で「届いた」と誤認するので isError (P2-8)。
                this.opts.logger.warn("reply_drop_no_hub", { chat_id: args.chat_id });
                return {
                    isError: true,
                    content: [{ type: "text", text: "hub disconnected; reply dropped" }],
                };
            }
            this.opts.logger.info("reply_sent", {
                chat_id: args.chat_id,
                text_len: args.text.length,
            });
            return { content: [{ type: "text", text: "sent" }] };
        });

        this.server.setNotificationHandler(PermissionRequestNotification, async ({ params }) => {
            this.opts.hub.sendPermission({
                session_id: this.opts.sessionId,
                request_id: params.request_id,
                tool_name: params.tool_name,
                description: params.description,
                input_preview: params.input_preview,
            });
            this.opts.logger.info("permission_request_forwarded", {
                request_id: params.request_id,
                tool_name: params.tool_name,
            });
        });

        this.server.setNotificationHandler(PermissionAbortNotification, async ({ params }) => {
            this.opts.hub.sendPermissionAbort(params.request_id, params.reason);
            this.opts.logger.info("permission_abort_forwarded", {
                request_id: params.request_id,
                reason: params.reason ?? "",
            });
        });
    }

    private async notify(method: string, params: Record<string, unknown>): Promise<void> {
        try {
            await this.server.notification({ method, params });
        } catch (err) {
            this.opts.logger.warn("notification_failed", {
                method,
                error: (err as Error).message,
            });
        }
    }
}
