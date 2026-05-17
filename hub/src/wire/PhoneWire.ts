// docs/02 §4.3.1 / docs/03 §5.4: Phone ↔ Hub HTTP/JSON wire 型。
// snake_case 維持 — Kotlin :protocol golden と parity (§2.6 双方向検証)。

// --- Phone → Hub (HTTP POST) ---

export type SendRequest = {
    text: string;
    session_id?: string;
    image_base64?: string;
    image_mime?: string;
};

export type SendResponse = {
    chat_id: string;
    session_id?: string;
};

export type PermissionRequest = {
    request_id: string;
    behavior: "allow" | "deny";
};

// --- Hub → Phone (SSE /events) ---

export type ReplySse = {
    type: "reply";
    chat_id: string;
    session_id?: string;
    text: string;
};

export type PermissionSse = {
    type: "permission";
    request_id: string;
    session_id?: string;
    tool_name: string;
    description: string;
    input_preview: string;
};

export type PermissionAbortSse = {
    type: "permission_abort";
    request_id: string;
    reason?: string;
};

export type SessionActiveSse = {
    type: "session_active";
    session_id: string;
};

export type SessionInactiveSse = {
    type: "session_inactive";
    session_id: string;
};

export type SessionSnapshotSse = {
    type: "session_snapshot";
    active_session_ids: string[];
};

export type PermissionSnapshotSse = {
    type: "permission_snapshot";
    request_ids: string[];
};

export type PhoneSseEvent =
    | ReplySse
    | PermissionSse
    | PermissionAbortSse
    | SessionActiveSse
    | SessionInactiveSse
    | SessionSnapshotSse
    | PermissionSnapshotSse;

export type PhoneSseEventType = PhoneSseEvent["type"];

/** docs/03 §5.2.3 / §5.2.4: state class に型変換責務を持たせない純粋変換。session_id null は key 省略 (exactOptionalPropertyTypes)。 */
export function permissionEntryToSse(entry: {
    requestId: string;
    sessionId: string | null;
    toolName: string;
    description: string;
    inputPreview: string;
}): PermissionSse {
    const sse: PermissionSse = {
        type: "permission",
        request_id: entry.requestId,
        tool_name: entry.toolName,
        description: entry.description,
        input_preview: entry.inputPreview,
    };
    if (entry.sessionId !== null) sse.session_id = entry.sessionId;
    return sse;
}
