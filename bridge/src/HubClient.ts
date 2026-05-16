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
        // socket "error" は connect 前後で発火し得るので、`once("connect")` より先に on() を張る
        // (P3-5: once解除 → 未登録の隙間で error → Unhandled error throw を避ける)。
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

        // register write 失敗時は connect() を fail させる (P1-3)。
        // writeNow が false を返すケース = socket destroyed / 同期 throw。
        // backpressure はここでは true を返す側に倒している (true でも warn ログは出る)。
        const ok = this.writeRegisterNow();
        if (!ok) {
            this.close();
            throw new Error("register write failed immediately after connect");
        }
    }

    /**
     * `register` だけは callback で実際の送出を確認したい (kernel buffer に乗ったかは確認可能、
     * Hub が受信して ack を返すかは別経路で watchdog する設計余地あり)。今は sync の write 結果で判定。
     */
    private writeRegisterNow(): boolean {
        return this.writeNow({
            type: "register",
            session_id: this.opts.sessionId,
            pid: this.opts.pid ?? process.pid,
        });
    }

    /**
     * 戻り値: 送信を queue or write できた場合 true、socket が無く drop した場合 false。
     * Bridge は 1 プロセス = 1 session なので session_id は HubClient が抱え持つ
     * (`opts.sessionId`)。呼び出し側で再渡しは不要 (P2-6)。
     */
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
