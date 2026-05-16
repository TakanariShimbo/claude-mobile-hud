// Phone ↔ Hub の HTTP/JSON wire 型。Phase 2 §4.3.1 / Phase 3 §5.4。
// snake_case を維持 (Kotlin :protocol の golden と parity を取れる shape)。

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

/**
 * OutstandingEntry → PermissionSse の純粋変換。state class が型変換責務を持たないよう
 * ここに置く。session_id が null なら key 自体を省略 (`exactOptionalPropertyTypes` 準拠)。
 */
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
