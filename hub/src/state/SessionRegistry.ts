// session_id ↔ Bridge socket の map。Phase 3 §5.2.3 周辺。
// Hub 内 in-memory のみ。Hub 再起動で空 (FR-HU-15)。

import type { Socket } from "node:net";

export interface SessionEntry {
    sessionId: string;
    bridgeSessionId: string; // BridgeServer が socket ごとに発行する内部 ID
    pid: number;
    socket: Socket;
    registeredAtMs: number;
}

export class SessionRegistry {
    private readonly bySessionId = new Map<string, SessionEntry>();

    register(entry: SessionEntry): void {
        this.bySessionId.set(entry.sessionId, entry);
    }

    unregister(sessionId: string): SessionEntry | undefined {
        const entry = this.bySessionId.get(sessionId);
        if (entry) this.bySessionId.delete(sessionId);
        return entry;
    }

    unregisterByBridgeSessionId(bridgeSessionId: string): SessionEntry[] {
        const removed: SessionEntry[] = [];
        for (const [sid, entry] of this.bySessionId) {
            if (entry.bridgeSessionId === bridgeSessionId) {
                this.bySessionId.delete(sid);
                removed.push(entry);
            }
        }
        return removed;
    }

    get(sessionId: string): SessionEntry | undefined {
        return this.bySessionId.get(sessionId);
    }

    activeIds(): string[] {
        return Array.from(this.bySessionId.keys());
    }

    size(): number {
        return this.bySessionId.size;
    }
}
