// Phone → Hub の `send` で発行する chat_id を採番。
// Phase 2 §4.6 表に従い UUID v4。 Hub 内 in-memory、再起動でカウンタ等の状態は無い。

import { randomUUID } from "node:crypto";

export class ChatRegistry {
    /** UUID v4 を生成して返す。 */
    mint(): string {
        return randomUUID();
    }
}
