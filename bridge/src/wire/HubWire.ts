// Hub ↔ Bridge の TCP NDJSON wire 型 (Bridge 側の視点)。
// Hub 側 (hub/src/wire/BridgeWire.ts) と表裏一体。Phase 2 §4.3.2 / Phase 3 §6.1。
// shape は完全に一致させること (parity)。

// --- Bridge → Hub ---

export type RegisterMessage = {
    type: "register";
    session_id: string;
    pid: number;
};

export type ReplyMessage = {
    type: "reply";
    chat_id: string;
    session_id: string;
    text: string;
};

export type PermissionMessage = {
    type: "permission";
    request_id: string;
    session_id: string;
    tool_name: string;
    description: string;
    input_preview: string;
};

export type PermissionAbortMessage = {
    type: "permission_abort";
    request_id: string;
    reason?: string;
};

export type BridgeToHubMessage =
    | RegisterMessage
    | ReplyMessage
    | PermissionMessage
    | PermissionAbortMessage;

// --- Hub → Bridge ---

export type AckRegisterMessage = {
    type: "ack_register";
    chat_id_seed?: string;
};

export type SendMessage = {
    type: "send";
    chat_id: string;
    text: string;
    /** Phone 由来の base64 画像 (data: prefix なし)。Bridge 側で staging して image_path を Claude へ。 */
    image_base64?: string;
    /** image_base64 の MIME (例: "image/jpeg")。 */
    image_mime?: string;
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
