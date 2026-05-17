// docs/02 §4.3.2 / docs/03 §6.1: Hub ↔ Bridge NDJSON wire 型 (Bridge 視点)。
// Hub 側 hub/src/wire/BridgeWire.ts と shape parity (HubClient.handleLine の exhaustive guard §6.2.2.7 で機械検出)。

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
