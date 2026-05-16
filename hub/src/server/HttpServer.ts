// Phone ↔ Hub の HTTP/SSE server。Phase 3 §5.2.2 / §5.2.4。
//
// エンドポイント:
//   POST /send         text/image を Bridge 経由で Claude へ
//   POST /permission   verdict を Bridge 経由で Claude へ
//   GET  /events       SSE。接続成立直後に session_snapshot → permission_snapshot → 個別 permission

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { ChatRegistry } from "../state/ChatRegistry.js";
import { OutstandingPermissions } from "../state/OutstandingPermissions.js";
import { SessionRegistry } from "../state/SessionRegistry.js";
import { SseClients } from "./SseClients.js";
import { ERROR_CODES, ERROR_HTTP_STATUS, type ErrorCode } from "../wire/ErrorCodes.js";
import type { HubToBridgeMessage } from "../wire/BridgeWire.js";
import type {
    PermissionRequest,
    PermissionSnapshotSse,
    PhoneSseEvent,
    SendRequest,
    SendResponse,
    SessionSnapshotSse,
} from "../wire/PhoneWire.js";
import type { Logger } from "../log/StructuredLog.js";

/** Bridge への送信ハンドル (HttpServer は Bridge 実装の詳細に依存しない)。 */
export type BridgeDispatcher = (sessionId: string, msg: HubToBridgeMessage) => boolean;

export interface HttpServerDeps {
    sessions: SessionRegistry;
    chats: ChatRegistry;
    outstanding: OutstandingPermissions;
    dispatchToBridge: BridgeDispatcher;
    logger: Logger;
    token: string | null;
    sseKeepAliveMs: number;
}

export class HttpServer {
    private readonly server: Server;
    private readonly sse: SseClients;
    private keepAliveTimer: NodeJS.Timeout | null = null;

    constructor(private readonly deps: HttpServerDeps) {
        this.sse = new SseClients(deps.logger);
        this.server = createServer((req, res) => {
            this.handle(req, res).catch((err) => {
                this.deps.logger.error("http_unhandled", { error: (err as Error).message });
                if (!res.headersSent) {
                    this.sendError(res, ERROR_CODES.INTERNAL_ERROR, "unhandled");
                }
            });
        });
    }

    /** BridgeServer.deps.phoneBroadcast に渡す */
    broadcast(event: PhoneSseEvent): void {
        this.sse.broadcast(event);
    }

    async listen(port: number, host: string = "0.0.0.0"): Promise<void> {
        await new Promise<void>((resolve, reject) => {
            const onError = (err: Error) => {
                this.server.off("listening", onListening);
                reject(err);
            };
            const onListening = () => {
                this.server.off("error", onError);
                resolve();
            };
            this.server.once("error", onError);
            this.server.once("listening", onListening);
            this.server.listen(port, host);
        });
        this.keepAliveTimer = setInterval(() => this.sse.sendKeepAlive(), this.deps.sseKeepAliveMs);
        this.deps.logger.info("listen", { port, host });
    }

    async close(): Promise<void> {
        if (this.keepAliveTimer) clearInterval(this.keepAliveTimer);
        this.keepAliveTimer = null;
        this.sse.closeAll();
        await new Promise<void>((resolve) => this.server.close(() => resolve()));
    }

    private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
        if (!this.checkAuth(req, res)) return;
        const url = req.url ?? "/";
        const method = req.method ?? "GET";

        if (method === "POST" && url === "/send") return this.handleSend(req, res);
        if (method === "POST" && url === "/permission") return this.handlePermission(req, res);
        if (method === "GET" && url === "/events") return this.handleEvents(req, res);

        res.statusCode = 404;
        res.end();
    }

    private checkAuth(req: IncomingMessage, res: ServerResponse): boolean {
        if (this.deps.token === null) return true;
        const provided = req.headers["x-token"];
        const tokenStr = Array.isArray(provided) ? provided[0] : provided;
        if (tokenStr !== this.deps.token) {
            this.sendError(res, ERROR_CODES.AUTH_FAILED, "X-Token mismatch");
            return false;
        }
        return true;
    }

    private async handleSend(req: IncomingMessage, res: ServerResponse): Promise<void> {
        const body = await readJsonBody<SendRequest>(req);
        if (!body || typeof body.text !== "string") {
            this.sendError(res, ERROR_CODES.INVALID_PAYLOAD, "missing text");
            return;
        }
        const sessionId = this.resolveSessionId(body.session_id);
        if (sessionId === null) {
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, "no active session");
            return;
        }

        const chatId = this.deps.chats.mint();
        const sent = this.deps.dispatchToBridge(sessionId, {
            type: "send",
            chat_id: chatId,
            text: body.text,
            // image_path: Phase 4 で ImageStaging を入れた段階で結線
        });
        if (!sent) {
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, "session disappeared");
            return;
        }

        const payload: SendResponse = { chat_id: chatId, session_id: sessionId };
        this.sendJson(res, 200, payload);
        this.deps.logger.info("send", {
            session_id: sessionId,
            chat_id: chatId,
            text_len: body.text.length,
        });
    }

    private async handlePermission(req: IncomingMessage, res: ServerResponse): Promise<void> {
        const body = await readJsonBody<PermissionRequest>(req);
        if (!body || typeof body.request_id !== "string" ||
            (body.behavior !== "allow" && body.behavior !== "deny")) {
            this.sendError(res, ERROR_CODES.INVALID_PAYLOAD, "missing request_id/behavior");
            return;
        }
        const entry = this.deps.outstanding.remove(body.request_id);
        if (!entry) {
            this.sendError(res, ERROR_CODES.PERMISSION_GONE, "verdict already sent or unknown");
            return;
        }
        if (entry.sessionId === null) {
            this.deps.logger.warn("verdict_no_session", { request_id: body.request_id });
        } else {
            const sent = this.deps.dispatchToBridge(entry.sessionId, {
                type: "permission_verdict",
                request_id: body.request_id,
                behavior: body.behavior,
            });
            if (!sent) {
                this.deps.logger.warn("verdict_dispatch_failed", {
                    request_id: body.request_id,
                    session_id: entry.sessionId,
                });
            }
        }
        res.statusCode = 200;
        res.setHeader("Content-Type", "application/json");
        res.end("{}");
        this.deps.logger.info("permission_verdict", {
            request_id: body.request_id,
            behavior: body.behavior,
            session_id: entry.sessionId ?? "",
        });
    }

    private handleEvents(req: IncomingMessage, res: ServerResponse): Promise<void> {
        res.statusCode = 200;
        res.setHeader("Content-Type", "text/event-stream");
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("Connection", "keep-alive");
        res.flushHeaders?.();
        this.sse.add(res);

        // §5.2.4: snapshot 順送
        const sessionSnapshot: SessionSnapshotSse = {
            type: "session_snapshot",
            active_session_ids: this.deps.sessions.activeIds(),
        };
        this.sse.send(res, sessionSnapshot);

        const snap = this.deps.outstanding.buildSnapshot();
        const permissionSnapshot: PermissionSnapshotSse = {
            type: "permission_snapshot",
            request_ids: snap.requestIds,
        };
        this.sse.send(res, permissionSnapshot);

        for (const entry of snap.entries) {
            this.sse.send(res, OutstandingPermissions.toSse(entry));
        }

        this.deps.logger.info("sse_connect", {
            active_sessions: this.deps.sessions.size(),
            outstanding: snap.requestIds.length,
        });
        return Promise.resolve();
    }

    /** session_id 指定なし時の解決: 1 セッションなら自動採用、複数なら null。 */
    private resolveSessionId(requested: string | undefined): string | null {
        if (requested) {
            return this.deps.sessions.get(requested) ? requested : null;
        }
        const ids = this.deps.sessions.activeIds();
        return ids.length === 1 ? ids[0]! : null;
    }

    private sendJson(res: ServerResponse, status: number, body: unknown): void {
        res.statusCode = status;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify(body));
    }

    private sendError(res: ServerResponse, code: ErrorCode, message: string): void {
        this.sendJson(res, ERROR_HTTP_STATUS[code], { error_code: code, message });
    }
}

async function readJsonBody<T>(req: IncomingMessage): Promise<T | null> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
        chunks.push(typeof chunk === "string" ? Buffer.from(chunk) : chunk);
        if (totalBytes(chunks) > 16 * 1024 * 1024) return null; // 16 MB ceiling (image_base64 想定)
    }
    const raw = Buffer.concat(chunks).toString("utf8");
    if (!raw) return null;
    try {
        return JSON.parse(raw) as T;
    } catch {
        return null;
    }
}

function totalBytes(chunks: Buffer[]): number {
    let n = 0;
    for (const c of chunks) n += c.length;
    return n;
}
