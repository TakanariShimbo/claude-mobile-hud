// Hub への TCP NDJSON 接続。Phase 3 §6.2.2 / §1.3 D-中。
//
// 接続成立後、`register` を最初に送る。Hub から `ack_register` が返るまでは
// 他のメッセージ (reply / permission / permission_abort) は outgoingQueue に積み、
// ack 到着後に順送で flush する (D-中 race 対策)。

import { createConnection, type Socket } from "node:net";
import { createInterface } from "node:readline";
import type {
    BridgeToHubMessage,
    HubToBridgeMessage,
    PermissionMessage,
    PermissionAbortMessage,
    PermissionVerdictMessage,
    ReplyMessage,
    SendMessage,
} from "./wire/HubWire.js";
import type { Logger } from "./log/StructuredLog.js";

export interface HubClientCallbacks {
    onSend: (msg: SendMessage) => void;
    onPermissionVerdict: (msg: PermissionVerdictMessage) => void;
    onClose: (reason: "remote" | "local") => void;
}

export interface HubClientOptions {
    host: string;
    port: number;
    sessionId: string;
    pid?: number;
    callbacks: HubClientCallbacks;
    logger: Logger;
}

export class HubClient {
    private socket: Socket | null = null;
    private registered = false;
    private outgoingQueue: BridgeToHubMessage[] = [];
    private closed = false;

    constructor(private readonly opts: HubClientOptions) {}

    /**
     * Hub に接続して `register` を送る。Promise は `register` の write 完了で resolve するが、
     * `ack_register` を待たない。ack 前に来た send/permission は呼び出し側で queue。
     */
    async connect(): Promise<void> {
        const sock = createConnection({ host: this.opts.host, port: this.opts.port });
        await new Promise<void>((resolve, reject) => {
            sock.once("connect", () => resolve());
            sock.once("error", (err) => reject(err));
        });
        this.socket = sock;
        this.opts.logger.info("connected", { host: this.opts.host, port: this.opts.port });

        sock.on("error", (err) => this.opts.logger.warn("socket_error", { error: err.message }));
        sock.on("close", () => this.onClose());

        const rl = createInterface({ input: sock });
        rl.on("line", (line) => this.handleLine(line));

        this.writeNow({
            type: "register",
            session_id: this.opts.sessionId,
            pid: this.opts.pid ?? process.pid,
        });
    }

    sendReply(chatId: string, sessionId: string, text: string): void {
        const msg: ReplyMessage = { type: "reply", chat_id: chatId, session_id: sessionId, text };
        this.enqueueOrSend(msg);
    }

    sendPermission(msg: Omit<PermissionMessage, "type">): void {
        this.enqueueOrSend({ type: "permission", ...msg });
    }

    sendPermissionAbort(requestId: string, reason?: string): void {
        const msg: PermissionAbortMessage = { type: "permission_abort", request_id: requestId };
        if (reason !== undefined) msg.reason = reason;
        this.enqueueOrSend(msg);
    }

    close(): void {
        if (this.closed) return;
        this.closed = true;
        try {
            this.socket?.end();
            this.socket?.destroy();
        } catch {
            /* ignore */
        }
    }

    isRegistered(): boolean {
        return this.registered;
    }

    queueSize(): number {
        return this.outgoingQueue.length;
    }

    private enqueueOrSend(msg: BridgeToHubMessage): void {
        if (this.registered) {
            this.writeNow(msg);
        } else {
            this.outgoingQueue.push(msg);
            this.opts.logger.debug("queued_pre_ack", { type: msg.type, queue_size: this.outgoingQueue.length });
        }
    }

    private writeNow(msg: BridgeToHubMessage): boolean {
        const sock = this.socket;
        if (!sock || sock.destroyed || sock.writableEnded) {
            this.opts.logger.warn("write_dropped", { type: msg.type, reason: "no_socket" });
            return false;
        }
        try {
            const ok = sock.write(JSON.stringify(msg) + "\n");
            if (!ok) {
                this.opts.logger.warn("write_backpressure", { type: msg.type });
            }
            return true;
        } catch (err) {
            this.opts.logger.warn("write_failed", { type: msg.type, error: (err as Error).message });
            return false;
        }
    }

    private flushQueue(): void {
        if (this.outgoingQueue.length === 0) return;
        this.opts.logger.info("flush_queue", { size: this.outgoingQueue.length });
        const queued = this.outgoingQueue;
        this.outgoingQueue = [];
        for (const msg of queued) this.writeNow(msg);
    }

    private handleLine(line: string): void {
        const trimmed = line.trim();
        if (trimmed.length === 0) return;
        let msg: HubToBridgeMessage;
        try {
            msg = JSON.parse(trimmed) as HubToBridgeMessage;
        } catch {
            this.opts.logger.warn("parse_error", { line_head: trimmed.slice(0, 80) });
            return;
        }
        switch (msg.type) {
            case "ack_register":
                this.registered = true;
                this.opts.logger.info("ack_register");
                this.flushQueue();
                return;
            case "send":
                this.opts.callbacks.onSend(msg);
                return;
            case "permission_verdict":
                this.opts.callbacks.onPermissionVerdict(msg);
                return;
            default: {
                const _exhaust: never = msg;
                void _exhaust;
                this.opts.logger.warn("unknown_message", { line_head: trimmed.slice(0, 80) });
                return;
            }
        }
    }

    private onClose(): void {
        const reason: "remote" | "local" = this.closed ? "local" : "remote";
        this.opts.logger.info("disconnected", { reason });
        this.socket = null;
        this.registered = false;
        this.opts.callbacks.onClose(reason);
    }
}
