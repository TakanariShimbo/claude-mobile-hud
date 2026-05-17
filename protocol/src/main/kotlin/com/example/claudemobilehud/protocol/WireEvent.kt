package com.example.claudemobilehud.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WireEvent {
    /** docs/03 §2.2.1: epoch ms。Long で JS Number 安全範囲 (2^53-1) に収まる。 */
    val ts: Long
}

// --- Phone↔Glass: state push ---

@Serializable
@SerialName("current_state")
data class CurrentState(
    val seq: Int,
    val mode: ConversationMode,
    @SerialName("pending_permission") val pendingPermission: PendingPermissionPayload?,
    @SerialName("transcript_state") val transcriptState: TranscriptState,
    @SerialName("transcript_text") val transcriptText: String,
    @SerialName("input_text") val inputText: String,
    @SerialName("mic_source") val micSource: MicSource,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("input_text_only")
data class InputTextOnly(
    @SerialName("parent_seq") val parentSeq: Int,
    @SerialName("input_text") val inputText: String,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("session_list")
data class SessionList(
    val sessions: List<SessionSummaryPayload>,
    override val ts: Long,
) : WireEvent

/** docs/03 §2.2.2: `id == null` = 現在 session 無しの clear セマンティクス。 */
@Serializable
@SerialName("current_session")
data class CurrentSessionEvent(
    val id: String?,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("messages")
data class MessagesEvent(
    @SerialName("session_id") val sessionId: String?,
    val messages: List<ChatMessagePayload>,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("notification")
data class NotificationEvent(
    val kind: NotificationKind,
    val text: String,
    @SerialName("session_id") val sessionId: String?,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("error")
data class ErrorEvent(
    val message: String,
    override val ts: Long,
) : WireEvent

// --- Glass→Phone ---
// docs/03 §2.2.3: 以下 `ts` のみ data class は同 ts で equals 衝突する。de-dup 時は (kind, 受信時刻) で判定。

@Serializable
@SerialName("hello")
data class Hello(override val ts: Long) : WireEvent

@Serializable
@SerialName("select_session")
data class SelectSession(
    val id: String,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("gesture")
data class GestureEvent(
    val which: GestureKind,
    override val ts: Long,
) : WireEvent

@Serializable
@SerialName("listening_cancel")
data class ListeningCancel(override val ts: Long) : WireEvent

@Serializable
@SerialName("permission_verdict")
data class PermissionVerdictEvent(
    @SerialName("request_id") val requestId: String,
    val decision: PermissionDecision,
    override val ts: Long,
) : WireEvent

// --- CXR session lifecycle ---

@Serializable
@SerialName("session_open")
data class SessionOpen(override val ts: Long) : WireEvent

@Serializable
@SerialName("session_close")
data class SessionClose(override val ts: Long) : WireEvent

@Serializable
@SerialName("ping")
data class Ping(override val ts: Long) : WireEvent

// --- 補助 payload data class ---

@Serializable
data class PendingPermissionPayload(
    @SerialName("request_id") val requestId: String,
    @SerialName("tool_name") val toolName: String,
    val description: String,
    @SerialName("input_preview") val inputPreview: String,
    @SerialName("session_id") val sessionId: String?,
    @SerialName("created_at_ms") val createdAtMs: Long,
)

@Serializable
data class SessionSummaryPayload(
    val id: String,
    val label: String,
    @SerialName("message_count") val messageCount: Int,
)

@Serializable
data class ChatMessagePayload(
    /** docs/03 §2.2.4: HistoryStore autoinc。2^53 上限まで余裕。 */
    val id: Long,
    val role: MessageRole,
    val text: String,
    @SerialName("chat_id") val chatId: String?,
)
