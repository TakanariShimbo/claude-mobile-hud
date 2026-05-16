// SSE client の管理。HttpServer の内部で使う。

import type { ServerResponse } from "node:http";
import type { PhoneSseEvent } from "../wire/PhoneWire.js";
import type { Logger } from "../log/StructuredLog.js";

export class SseClients {
    private readonly clients = new Set<ServerResponse>();

    constructor(private readonly logger: Logger) {}

    add(res: ServerResponse): void {
        this.clients.add(res);
        res.on("close", () => this.clients.delete(res));
    }

    remove(res: ServerResponse): void {
        this.clients.delete(res);
    }

    size(): number {
        return this.clients.size;
    }

    send(res: ServerResponse, event: PhoneSseEvent): boolean {
        if (res.writableEnded || res.destroyed) {
            this.clients.delete(res);
            return false;
        }
        const payload = `event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`;
        try {
            res.write(payload);
            return true;
        } catch (err) {
            this.logger.warn("sse_write_failed", { error: (err as Error).message });
            this.clients.delete(res);
            return false;
        }
    }

    broadcast(event: PhoneSseEvent): number {
        let delivered = 0;
        for (const res of this.clients) {
            if (this.send(res, event)) delivered += 1;
        }
        return delivered;
    }

    sendKeepAlive(): void {
        for (const res of this.clients) {
            if (res.writableEnded || res.destroyed) {
                this.clients.delete(res);
                continue;
            }
            try {
                res.write(":\n\n"); // SSE comment line
            } catch {
                this.clients.delete(res);
            }
        }
    }

    closeAll(): void {
        for (const res of this.clients) {
            try {
                res.end();
            } catch {
                /* ignore */
            }
        }
        this.clients.clear();
    }
}
