// docs/02 §4.3.2 / docs/03 §5.1: Hub ↔ Bridge TCP NDJSON wire 型。
// Bridge は接続後最初に `register` を送る (§6.2.2.2 register-then-queue)。

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
    /** docs/03 §6.2.4: Phone 由来 base64 画像 (data: prefix なし)、Bridge が staging。 */
    image_base64?: string;
    /** docs/03 §6.2.4.3: MIME whitelist (image/jpeg|png|webp|gif)。 */
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
