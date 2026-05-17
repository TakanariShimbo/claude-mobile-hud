// docs/03 §5.2.3 / AD-13 / FR-HU-12〜15: permission の中央 state。
// 再起動で空 (FR-HU-15、永続化しない) / Bridge 切断時の一括 abort は §5.2.3.7。

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

    /** docs/03 §5.2.3.7: 当該 bridge 由来 outstanding を Phone へ一斉 abort (FR-HU-13)。 */
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

    /** docs/03 §5.2.4 / AD-13: SSE 再接続時 snapshot (createdAtMs 昇順、FR-HU-14)。 */
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
