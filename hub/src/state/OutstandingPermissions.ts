// permission の中央 state。Phase 3 §5.2.3 / AD-13 / FR-HU-12〜15。
// - add / remove: Bridge から permission を受けたとき / verdict を返したとき
// - onBridgeDisconnected (FR-HU-13): bridge 切断時に当該 outstanding を Phone に abort push
// - buildSnapshot (AD-13 / FR-HU-14): SSE 再接続時に Phone へ ID 集合と各 entry を再 push
// - Hub 再起動時は空 (FR-HU-15) — 永続化しない

import type { PermissionAbortSse } from "../wire/PhoneWire.js";

export interface OutstandingEntry {
    requestId: string;
    sessionId: string | null;
    toolName: string;
    description: string;
    inputPreview: string;
    createdAtMs: number;
    bridgeSessionId: string;
}

export class OutstandingPermissions {
    private readonly entries = new Map<string, OutstandingEntry>();

    add(entry: OutstandingEntry): void {
        this.entries.set(entry.requestId, entry);
    }

    has(requestId: string): boolean {
        return this.entries.has(requestId);
    }

    get(requestId: string): OutstandingEntry | undefined {
        return this.entries.get(requestId);
    }

    remove(requestId: string): OutstandingEntry | undefined {
        const entry = this.entries.get(requestId);
        if (entry) this.entries.delete(requestId);
        return entry;
    }

    size(): number {
        return this.entries.size;
    }

    /** FR-HU-13: Bridge 切断時、その bridge 由来の outstanding を全部 abort して push */
    onBridgeDisconnected(
        bridgeSessionId: string,
        pushToPhone: (event: PermissionAbortSse) => void,
    ): string[] {
        const abortedIds: string[] = [];
        for (const [rid, entry] of this.entries) {
            if (entry.bridgeSessionId === bridgeSessionId) {
                pushToPhone({ type: "permission_abort", request_id: rid, reason: "bridge_disconnected" });
                this.entries.delete(rid);
                abortedIds.push(rid);
            }
        }
        return abortedIds;
    }

    /** AD-13 / FR-HU-14: SSE 再接続時に Phone へ送る snapshot (createdAtMs 昇順) */
    buildSnapshot(): { requestIds: string[]; entries: OutstandingEntry[] } {
        const sorted = Array.from(this.entries.values()).sort(
            (a, b) => a.createdAtMs - b.createdAtMs,
        );
        return {
            requestIds: sorted.map((e) => e.requestId),
            entries: sorted,
        };
    }

}
