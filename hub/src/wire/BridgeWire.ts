// Hub ↔ Bridge の TCP NDJSON wire 型。Phase 2 §4.3.2 / Phase 3 §5.1。
// Bridge は接続後最初に `register` を送る。以後双方向に NDJSON (1 行 1 JSON)。

// --- Bridge → Hub ---

export type RegisterMessage = {
    type: "register";
    session_id: string;
    pid: number;
};

export type BridgeReplyMessage = {
    type: "reply";
    chat_id: string;
    session_id: string;
    text: string;
};

export type BridgePermissionMessage = {
    type: "permission";
    request_id: string;
    session_id: string;
    tool_name: string;
    description: string;
    input_preview: string;
};

export type BridgePermissionAbortMessage = {
    type: "permission_abort";
    request_id: string;
    reason?: string;
};

export type BridgeToHubMessage =
    | RegisterMessage
    | BridgeReplyMessage
    | BridgePermissionMessage
    | BridgePermissionAbortMessage;

// --- Hub → Bridge ---

export type AckRegisterMessage = {
    type: "ack_register";
    chat_id_seed?: string;
};

export type SendMessage = {
    type: "send";
    chat_id: string;
    text: string;
    image_path?: string;
};

export type PermissionVerdictMessage = {
    type: "permission_verdict";
    request_id: string;
    behavior: "allow" | "deny";
};

export type HubToBridgeMessage =
    | AckRegisterMessage
    | SendMessage
    | PermissionVerdictMessage;
