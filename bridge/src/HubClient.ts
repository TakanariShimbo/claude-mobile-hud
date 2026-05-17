// docs/03 §6.2.2: Hub への TCP NDJSON クライアント。§1.3 D-中 (register-then-queue) 対応。

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

    /** docs/03 §6.2.2.2: `register` の write 完了で resolve (ack_register は待たない)。 */
    async connect(): Promise<void> {
        const sock = createConnection({ host: this.opts.host, port: this.opts.port });
        // docs/03 §6.2.2.3: once("connect")/once("error") より先に永続 handler を張る (P3-5)。
        sock.on("error", (err) => this.opts.logger.warn("socket_error", { error: err.message }));
        sock.on("close", () => this.onClose());
        await new Promise<void>((resolve, reject) => {
            const onErr = (err: Error) => {
                sock.off("connect", onOk);
                reject(err);
            };
            const onOk = () => {
                sock.off("error", onErr);
                resolve();
            };
            sock.once("connect", onOk);
            sock.once("error", onErr);
        });
        this.socket = sock;
        this.opts.logger.info("connected", { host: this.opts.host, port: this.opts.port });

        const rl = createInterface({ input: sock });
        rl.on("line", (line) => this.handleLine(line));

        // docs/03 §6.2.2.4: register write 失敗 → connect() 自体を fail (P1-3)。
        const ok = this.writeRegisterNow();
        if (!ok) {
            this.close();
            throw new Error("register write failed immediately after connect");
        }
    }

    private writeRegisterNow(): boolean {
        return this.writeNow({
            type: "register",
            session_id: this.opts.sessionId,
            pid: this.opts.pid ?? process.pid,
        });
    }

    // docs/03 §6.2.2.5: sessionId は HubClient 内蔵 (P2-6)。戻り値契約は §6.2.2.8。
    sendReply(chatId: string, text: string): boolean {
        const msg: ReplyMessage = {
            type: "reply",
            chat_id: chatId,
            session_id: this.opts.sessionId,
            text,
        };
        return this.enqueueOrSend(msg);
    }

    sendPermission(msg: Omit<PermissionMessage, "type">): boolean {
        return this.enqueueOrSend({ type: "permission", ...msg });
    }

    sendPermissionAbort(requestId: string, reason?: string): boolean {
        const msg: PermissionAbortMessage = { type: "permission_abort", request_id: requestId };
        if (reason !== undefined) msg.reason = reason;
        return this.enqueueOrSend(msg);
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

    private enqueueOrSend(msg: BridgeToHubMessage): boolean {
        if (this.registered) {
            return this.writeNow(msg);
        }
        if (this.closed || this.socket === null) {
            this.opts.logger.warn("write_dropped", { type: msg.type, reason: "no_socket" });
            return false;
        }
        this.outgoingQueue.push(msg);
        this.opts.logger.debug("queued_pre_ack", { type: msg.type, queue_size: this.outgoingQueue.length });
        return true;
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
                // docs/03 §6.2.2.7: union に variant が増えたらコンパイル時に拒否させる exhaustive guard。
                const _exhaust: never = msg;
                void _exhaust;
                this.opts.logger.warn("unknown_message", { line_head: trimmed.slice(0, 80) });
                return;
            }
        }
    }

    private onClose(): void {
        // docs/03 §6.2.2.6: close() 経由なら local、それ以外は Hub からの切断 = remote。
        const reason: "remote" | "local" = this.closed ? "local" : "remote";
        this.opts.logger.info("disconnected", { reason });
        this.socket = null;
        this.registered = false;
        this.opts.callbacks.onClose(reason);
    }
}
