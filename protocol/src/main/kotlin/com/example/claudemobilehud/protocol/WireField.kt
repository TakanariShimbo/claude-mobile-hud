package com.example.claudemobilehud.protocol

/**
 * Wire 上のフィールド名 (snake_case)。@SerialName と同じ値を保持。
 *
 * 用途は:
 * - 構造化ログのキー (`tag=phone event=wire_send chat_id=...`)
 * - 手書きで JsonObject を組む / 探る場合の型安全な lookup
 *
 * `@SerialName` で encode/decode が自動化されているため、シリアライザを通る経路では
 * 直接参照する必要は無い。ここの定数は **シリアライザを介さない経路** のためにある。
 *
 * Phase 2 §4.4 (snake_case 規約) / Phase 3 §2.1 (file 配置) を参照。
 */
object WireField {
    // 相関 ID (Phase 2 §7.14, AD-12)
    const val SESSION_ID = "session_id"
    const val CHAT_ID = "chat_id"
    const val REQUEST_ID = "request_id"
    const val PARENT_SEQ = "parent_seq"
    const val SEQ = "seq"

    // discriminator (JsonCodec の classDiscriminator)
    const val EVENT = "event"

    // current_state
    const val MODE = "mode"
    const val PENDING_PERMISSION = "pending_permission"
    const val TRANSCRIPT_STATE = "transcript_state"
    const val TRANSCRIPT_TEXT = "transcript_text"
    const val INPUT_TEXT = "input_text"
    const val MIC_SOURCE = "mic_source"
    const val TS = "ts"

    // permission payload
    const val TOOL_NAME = "tool_name"
    const val DESCRIPTION = "description"
    const val INPUT_PREVIEW = "input_preview"
    const val CREATED_AT_MS = "created_at_ms"
    const val DECISION = "decision"

    // messages / sessions
    const val SESSIONS = "sessions"
    const val MESSAGES = "messages"
    const val ROLE = "role"
    const val TEXT = "text"
    const val LABEL = "label"
    const val MESSAGE_COUNT = "message_count"
    const val ID = "id"

    // notification / gesture / error
    const val KIND = "kind"
    const val WHICH = "which"
    const val MESSAGE = "message"
}
