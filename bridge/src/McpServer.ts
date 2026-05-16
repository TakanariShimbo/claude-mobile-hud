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
import {
    CallToolRequestSchema,
    ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import type { HubClient } from "./HubClient.js";
import { ImageStaging } from "./ImageStaging.js";
import type { SendMessage, PermissionVerdictMessage } from "./wire/HubWire.js";
import type { Logger } from "./log/StructuredLog.js";

const SERVER_NAME = "claude-mobile-hud-bridge";
const SERVER_VERSION = "0.1.0";

const INSTRUCTIONS =
    'Messages from a mobile client arrive as `notifications/claude/channel` with ' +
    '`meta.chat_id` and optional `meta.image_path`. Reply with the "reply" tool, ' +
    'passing the chat_id from the meta verbatim. Treat each chat_id as one conversation thread.';

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
}

export class McpServer {
    private readonly server: Server;
    // 画像書き込みは順序保証 (同 chat_id の inbound notification が image_save 完了前に
    // 後続 notification を Claude に届けてしまうのを防ぐ)
    private writeQueue: Promise<void> = Promise.resolve();

    constructor(private readonly opts: McpServerOptions) {
        this.server = new Server(
            { name: SERVER_NAME, version: SERVER_VERSION },
            {
                capabilities: {
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
        await this.server.connect(new StdioServerTransport());
        this.opts.logger.info("mcp_connected");
        this.server.onclose = () => {
            this.opts.logger.info("mcp_closed");
            this.opts.onClose();
        };
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
            this.opts.hub.sendReply(args.chat_id, this.opts.sessionId, args.text);
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
