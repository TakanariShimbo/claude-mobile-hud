// docs/03 §5.2.2: Phone ↔ Hub の HTTP/SSE server。
//   POST /send / POST /permission / GET /events (SSE snapshot は §5.2.4)

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { ChatRegistry } from "../state/ChatRegistry.js";
import type { OutstandingEntry, OutstandingPermissions } from "../state/OutstandingPermissions.js";
import { SessionRegistry } from "../state/SessionRegistry.js";
import { permissionEntryToSse } from "../wire/PhoneWire.js";
import { SseClients } from "./SseClients.js";
import { ERROR_CODES, ERROR_HTTP_STATUS, type ErrorCode } from "../wire/ErrorCodes.js";
import type {
    PermissionRequest,
    PermissionSnapshotSse,
    PhoneSseEvent,
    SendRequest,
    SendResponse,
    SessionSnapshotSse,
} from "../wire/PhoneWire.js";
import type { Logger } from "../log/StructuredLog.js";
import type { BridgeDispatcher } from "./BridgeServer.js";

export type { BridgeDispatcher } from "./BridgeServer.js";

export interface HttpServerDeps {
    sessions: SessionRegistry;
    chats: ChatRegistry;
    outstanding: OutstandingPermissions;
    dispatchToBridge: BridgeDispatcher;
    logger: Logger;
    token: string | null;
    sseKeepAliveMs: number;
}

// docs/03 §5.2.2.2: image_base64 を含むため大きめに取り、超過後は drain を続ける。
const BODY_LIMIT_BYTES = 16 * 1024 * 1024;

type ReadJsonError = "too_large" | "invalid_json" | "empty";
type ReadJsonResult<T> = { ok: true; body: T } | { ok: false; error: ReadJsonError };

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
        // querystring 付き (`/events?ts=12345`) を path 一致で許容。
        const path = new URL(req.url ?? "/", "http://x").pathname;
        const method = req.method ?? "GET";

        if (method === "POST" && path === "/send") return this.handleSend(req, res);
        if (method === "POST" && path === "/permission") return this.handlePermission(req, res);
        if (method === "GET" && path === "/events") return this.handleEvents(req, res);

        res.statusCode = 404;
        res.end();
    }

    private checkAuth(req: IncomingMessage, res: ServerResponse): boolean {
        const expected = this.deps.token;
        if (expected === null) return true;
        const provided = req.headers["x-token"];
        const tokenStr = Array.isArray(provided) ? provided[0] : provided;
        // docs/03 §5.2.2.1: timing-safe 比較 (NFR-20)。
        if (!constantTimeEquals(tokenStr ?? "", expected)) {
            this.sendError(res, ERROR_CODES.AUTH_FAILED, "X-Token mismatch");
            return false;
        }
        return true;
    }

    private async handleSend(req: IncomingMessage, res: ServerResponse): Promise<void> {
        const result = await readJsonBody<SendRequest>(req);
        if (!result.ok) {
            this.sendError(res, mapReadError(result.error, "send"), result.error);
            return;
        }
        const body = result.body;
        if (typeof body.text !== "string") {
            this.sendError(res, ERROR_CODES.INVALID_PAYLOAD, "missing text");
            return;
        }
        const sessionId = this.resolveSessionId(body.session_id);
        if (sessionId === null) {
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, "no active session");
            return;
        }

        const chatId = this.deps.chats.mint();
        const sendMsg: import("../wire/BridgeWire.js").SendMessage = {
            type: "send",
            chat_id: chatId,
            text: body.text,
        };
        // docs/03 §6.2.4: image は base64 のまま Bridge へ転送 (Bridge が staging)。
        if (typeof body.image_base64 === "string" && body.image_base64.length > 0) {
            sendMsg.image_base64 = body.image_base64;
        }
        if (typeof body.image_mime === "string" && body.image_mime.length > 0) {
            sendMsg.image_mime = body.image_mime;
        }
        const dispatch = this.deps.dispatchToBridge(sessionId, sendMsg);
        if (!dispatch.ok) {
            this.deps.logger.warn("send_dispatch_failed", {
                session_id: sessionId,
                reason: dispatch.reason,
            });
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, `session ${dispatch.reason}`);
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
        const result = await readJsonBody<PermissionRequest>(req);
        if (!result.ok) {
            this.sendError(res, mapReadError(result.error, "permission"), result.error);
            return;
        }
        const body = result.body;
        if (typeof body.request_id !== "string" ||
            (body.behavior !== "allow" && body.behavior !== "deny")) {
            this.sendError(res, ERROR_CODES.INVALID_PAYLOAD, "missing request_id/behavior");
            return;
        }
        const entry = this.deps.outstanding.remove(body.request_id);
        if (!entry) {
            this.sendError(res, ERROR_CODES.PERMISSION_GONE, "verdict already sent or unknown");
            return;
        }

        if (!this.dispatchVerdict(entry, body.behavior, res)) return;

        this.sendJson(res, 200, {});
        this.deps.logger.info("permission_verdict", {
            request_id: body.request_id,
            behavior: body.behavior,
            session_id: entry.sessionId ?? "",
        });
    }

    /** docs/03 §5.2.2.5 / §5.2.2.6: 失敗時は Phone abort broadcast + 4xx で同期 (P1-1)。 */
    private dispatchVerdict(
        entry: OutstandingEntry,
        behavior: "allow" | "deny",
        res: ServerResponse,
    ): boolean {
        if (entry.sessionId === null) {
            this.deps.logger.warn("verdict_no_session", { request_id: entry.requestId });
            this.broadcast({
                type: "permission_abort",
                request_id: entry.requestId,
                reason: "no_session",
            });
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, "verdict target session unknown");
            return false;
        }
        const result = this.deps.dispatchToBridge(entry.sessionId, {
            type: "permission_verdict",
            request_id: entry.requestId,
            behavior,
        });
        if (!result.ok) {
            this.deps.logger.warn("verdict_dispatch_failed", {
                request_id: entry.requestId,
                session_id: entry.sessionId,
                reason: result.reason,
            });
            this.broadcast({
                type: "permission_abort",
                request_id: entry.requestId,
                reason: `dispatch_failed:${result.reason}`,
            });
            this.sendError(res, ERROR_CODES.SESSION_NOT_ACTIVE, `session ${result.reason}`);
            return false;
        }
        return true;
    }

    private handleEvents(req: IncomingMessage, res: ServerResponse): Promise<void> {
        res.statusCode = 200;
        res.setHeader("Content-Type", "text/event-stream");
        res.setHeader("Cache-Control", "no-cache, no-transform");
        res.setHeader("Connection", "keep-alive");
        res.flushHeaders?.();
        this.sse.add(res);

        // docs/03 §5.2.2.7 / §5.2.4: 接続成立直後の snapshot 順送。
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
            this.sse.send(res, permissionEntryToSse(entry));
        }

        this.deps.logger.info("sse_connect", {
            active_sessions: this.deps.sessions.size(),
            outstanding: snap.requestIds.length,
        });
        return Promise.resolve();
    }

    /** docs/03 §5.2.2.4: 単一 active session で session_id 省略時の自動採用。 */
    private resolveSessionId(requested: string | undefined): string | null {
        if (requested) {
            return this.deps.sessions.get(requested) ? requested : null;
        }
        const ids = this.deps.sessions.activeIds();
        if (ids.length !== 1) return null;
        const only = ids[0];
        return only ?? null;
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

// docs/03 §5.2.2.3: /send は IMAGE_TOO_LARGE、/permission は INVALID_PAYLOAD に倒す。
function mapReadError(err: ReadJsonError, endpoint: "send" | "permission"): ErrorCode {
    if (err === "too_large") {
        return endpoint === "send" ? ERROR_CODES.IMAGE_TOO_LARGE : ERROR_CODES.INVALID_PAYLOAD;
    }
    return ERROR_CODES.INVALID_PAYLOAD;
}

async function readJsonBody<T>(req: IncomingMessage): Promise<ReadJsonResult<T>> {
    const chunks: Buffer[] = [];
    let size = 0;
    let overLimit = false;
    for await (const chunk of req) {
        const buf = typeof chunk === "string" ? Buffer.from(chunk) : chunk;
        size += buf.length;
        if (size > BODY_LIMIT_BYTES) {
            // docs/03 §5.2.2.2: req.destroy() すると client が ECONNRESET になるので drain 継続。
            overLimit = true;
            continue;
        }
        chunks.push(buf);
    }
    if (overLimit) return { ok: false, error: "too_large" };
    const raw = Buffer.concat(chunks).toString("utf8");
    if (raw.length === 0) return { ok: false, error: "empty" };
    try {
        return { ok: true, body: JSON.parse(raw) as T };
    } catch {
        return { ok: false, error: "invalid_json" };
    }
}

function constantTimeEquals(a: string, b: string): boolean {
    const ba = Buffer.from(a, "utf8");
    const bb = Buffer.from(b, "utf8");
    if (ba.length !== bb.length) return false;
    return timingSafeEqual(ba, bb);
}
