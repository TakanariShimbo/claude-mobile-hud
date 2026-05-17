// docs/02 §4.6 / docs/03 §5.1: chat_id を UUID v4 で採番。Hub 内 in-memory、再起動で空 (FR-HU-15)。

import { randomUUID } from "node:crypto";

export class ChatRegistry {
    mint(): string {
        return randomUUID();
    }
}
