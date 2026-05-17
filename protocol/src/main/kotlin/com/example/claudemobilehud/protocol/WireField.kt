package com.example.claudemobilehud.protocol

/** docs/03 §2.7: シリアライザを介さない経路 (構造化ログ / 手書き JsonObject) 用の field 名定数。 */
object WireField {
    // 相関 ID (AD-12)
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
