// Bridge ↔ Hub の TCP NDJSON server。Phase 3 §5.2.3 / §5.2.4。
// Loopback (127.0.0.1) でしか listen しない (NFR-22)。

import { createServer, type Server, type Socket } from "node:net";
import { randomUUID } from "node:crypto";
import { SessionRegistry } from "../state/SessionRegistry.js";
import { OutstandingPermissions } from "../state/OutstandingPermissions.js";
import type {
    AckRegisterMessage,
    BridgePermissionAbortMessage,
    BridgePermissionMessage,
    BridgeReplyMessage,
    BridgeToHubMessage,
    HubToBridgeMessage,
    RegisterMessage,
} from "../wire/BridgeWire.js";
import type {
    PermissionAbortSse,
    PermissionSse,
    PhoneSseEvent,
    ReplySse,
    SessionActiveSse,
    SessionInactiveSse,
} from "../wire/PhoneWire.js";
import type { Logger } from "../log/StructuredLog.js";

export interface BridgeServerDeps {
    sessions: SessionRegistry;
    outstanding: OutstandingPermissions;
    phoneBroadcast: (event: PhoneSseEvent) => void;
    logger: Logger;
    now?: () => number;
}

interface SocketState {
    bridgeSessionId: string;
    sessionId: string | null;
    buffer: string;
}

export class BridgeServer {
    private readonly server: Server;
    private readonly sockets = new Map<Socket, SocketState>();
    private readonly now: () => number;

    constructor(private readonly deps: BridgeServerDeps) {
        this.now = deps.now ?? Date.now;
        this.server = createServer((socket) => this.onConnection(socket));
    }

    async listen(port: number, host: string = "127.0.0.1"): Promise<void> {
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
        this.deps.logger.info("listen", { port, host });
    }

    async close(): Promise<void> {
        for (const socket of this.sockets.keys()) {
            socket.destroy();
        }
        this.sockets.clear();
        await new Promise<void>((resolve) => this.server.close(() => resolve()));
    }

    sendToSession(sessionId: string, msg: HubToBridgeMessage): boolean {
        const entry = this.deps.sessions.get(sessionId);
        if (!entry) return false;
        return this.writeNdjson(entry.socket, msg);
    }

    private writeNdjson(socket: Socket, msg: HubToBridgeMessage): boolean {
        if (socket.destroyed) return false;
        socket.write(JSON.stringify(msg) + "\n");
        return true;
    }

    private onConnection(socket: Socket): void {
        const bridgeSessionId = randomUUID();
        this.sockets.set(socket, { bridgeSessionId, sessionId: null, buffer: "" });
        this.deps.logger.info("connect", { bridge_session_id: bridgeSessionId });

        socket.setEncoding("utf8");
        socket.on("data", (chunk: string) => this.onData(socket, chunk));
        socket.on("close", () => this.onClose(socket));
        socket.on("error", (err) =>
            this.deps.logger.warn("socket_error", {
                bridge_session_id: bridgeSessionId,
                error: err.message,
            }),
        );
    }

    private onData(socket: Socket, chunk: string): void {
        const state = this.sockets.get(socket);
        if (!state) return;
        state.buffer += chunk;

        let nl = state.buffer.indexOf("\n");
        while (nl >= 0) {
            const line = state.buffer.slice(0, nl).trim();
            state.buffer = state.buffer.slice(nl + 1);
            nl = state.buffer.indexOf("\n");
            if (line.length === 0) continue;
            this.handleLine(socket, state, line);
        }
    }

    private handleLine(socket: Socket, state: SocketState, line: string): void {
        let msg: BridgeToHubMessage;
        try {
            msg = JSON.parse(line) as BridgeToHubMessage;
        } catch (err) {
            this.deps.logger.warn("parse_error", {
                bridge_session_id: state.bridgeSessionId,
                line_head: line.slice(0, 80),
            });
            return;
        }

        switch (msg.type) {
            case "register":
                this.handleRegister(socket, state, msg);
                return;
            case "reply":
                this.handleReply(state, msg);
                return;
            case "permission":
                this.handlePermission(state, msg);
                return;
            case "permission_abort":
                this.handlePermissionAbort(state, msg);
                return;
            default: {
                const _exhaust: never = msg;
                this.deps.logger.warn("unknown_message", { line_head: line.slice(0, 80) });
                return _exhaust;
            }
        }
    }

    private handleRegister(socket: Socket, state: SocketState, msg: RegisterMessage): void {
        if (state.sessionId !== null) {
            this.deps.logger.warn("double_register", {
                bridge_session_id: state.bridgeSessionId,
                existing_session_id: state.sessionId,
                new_session_id: msg.session_id,
            });
            return;
        }
        state.sessionId = msg.session_id;
        this.deps.sessions.register({
            sessionId: msg.session_id,
            bridgeSessionId: state.bridgeSessionId,
            pid: msg.pid,
            socket,
            registeredAtMs: this.now(),
        });
        const ack: AckRegisterMessage = { type: "ack_register" };
        this.writeNdjson(socket, ack);
        const activate: SessionActiveSse = { type: "session_active", session_id: msg.session_id };
        this.deps.phoneBroadcast(activate);
        this.deps.logger.info("register", {
            session_id: msg.session_id,
            pid: msg.pid,
            bridge_session_id: state.bridgeSessionId,
        });
    }

    private handleReply(state: SocketState, msg: BridgeReplyMessage): void {
        if (state.sessionId === null) {
            this.deps.logger.warn("reply_before_register", {
                bridge_session_id: state.bridgeSessionId,
            });
            return;
        }
        const sse: ReplySse = {
            type: "reply",
            chat_id: msg.chat_id,
            session_id: msg.session_id,
            text: msg.text,
        };
        this.deps.phoneBroadcast(sse);
    }

    private handlePermission(state: SocketState, msg: BridgePermissionMessage): void {
        if (state.sessionId === null) {
            this.deps.logger.warn("permission_before_register", {
                bridge_session_id: state.bridgeSessionId,
            });
            return;
        }
        this.deps.outstanding.add({
            requestId: msg.request_id,
            sessionId: msg.session_id,
            toolName: msg.tool_name,
            description: msg.description,
            inputPreview: msg.input_preview,
            createdAtMs: this.now(),
            bridgeSessionId: state.bridgeSessionId,
        });
        const sse: PermissionSse = {
            type: "permission",
            request_id: msg.request_id,
            session_id: msg.session_id,
            tool_name: msg.tool_name,
            description: msg.description,
            input_preview: msg.input_preview,
        };
        this.deps.phoneBroadcast(sse);
    }

    private handlePermissionAbort(state: SocketState, msg: BridgePermissionAbortMessage): void {
        const removed = this.deps.outstanding.remove(msg.request_id);
        if (!removed) {
            this.deps.logger.debug("abort_unknown_request", { request_id: msg.request_id });
        }
        const sse: PermissionAbortSse = {
            type: "permission_abort",
            request_id: msg.request_id,
            reason: msg.reason,
        };
        this.deps.phoneBroadcast(sse);
    }

    private onClose(socket: Socket): void {
        const state = this.sockets.get(socket);
        this.sockets.delete(socket);
        if (!state) return;
        this.deps.logger.info("disconnect", {
            bridge_session_id: state.bridgeSessionId,
            session_id: state.sessionId ?? "",
        });
        if (state.sessionId !== null) {
            this.deps.sessions.unregister(state.sessionId);
            const inactivate: SessionInactiveSse = {
                type: "session_inactive",
                session_id: state.sessionId,
            };
            this.deps.phoneBroadcast(inactivate);
        }
        // FR-HU-13: 当該 bridge 由来の outstanding を abort
        this.deps.outstanding.onBridgeDisconnected(state.bridgeSessionId, (abort) =>
            this.deps.phoneBroadcast(abort),
        );
    }
}
