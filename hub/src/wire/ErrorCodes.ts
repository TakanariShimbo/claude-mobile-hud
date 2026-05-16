// Hub → Phone HTTP error_code (Phase 3 §5.2.1 / AD-21)。
// Phone 側 PhoneWireError へのマッピングは Phone 実装時に行う。

export const ERROR_CODES = {
    AUTH_FAILED: "auth_failed",
    PERMISSION_GONE: "permission_gone",
    IMAGE_TOO_LARGE: "image_too_large",
    SESSION_NOT_ACTIVE: "session_not_active",
    INVALID_PAYLOAD: "invalid_payload",
    INTERNAL_ERROR: "internal_error",
} as const;

export type ErrorCode = (typeof ERROR_CODES)[keyof typeof ERROR_CODES];

export type ErrorResponse = {
    error_code: ErrorCode;
    message: string;
};

export const ERROR_HTTP_STATUS: Record<ErrorCode, number> = {
    [ERROR_CODES.AUTH_FAILED]: 401,
    [ERROR_CODES.PERMISSION_GONE]: 410,
    [ERROR_CODES.IMAGE_TOO_LARGE]: 400,
    [ERROR_CODES.SESSION_NOT_ACTIVE]: 400,
    [ERROR_CODES.INVALID_PAYLOAD]: 400,
    [ERROR_CODES.INTERNAL_ERROR]: 500,
};
