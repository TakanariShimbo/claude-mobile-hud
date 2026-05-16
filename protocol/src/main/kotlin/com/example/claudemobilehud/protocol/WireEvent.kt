package com.example.claudemobilehud.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WireEvent {
    /**
     * Epoch ミリ秒。JS `Number` の安全範囲 (2^53 - 1) は西暦 287396 年まで余裕があるため、
     * TS 側で `number` として扱って精度欠落しない。
     */
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

/**
 * 現在 session の切替。`id == null` は **clear** (現在 session 無し) を意味する。
 * Glass 側受信時は null チェック 1 つで set/clear を分岐できる。
 */
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

// NOTE: 以下の `ts` のみ持つ data class は同 `ts` だと `equals == true`。
// 設計上 `ts` は単調増加なので衝突しない前提だが、将来 de-dup を入れる場合は
// (kind, 受信時刻) で判定し、`equals` には依存しないこと。

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
    /**
     * Phone-local HistoryStore の autoinc。実運用で 2^53 (約 9 千兆) に到達することは無いので
     * TS 側で `number` として安全に扱える。Phase 3 §3.6 の HistoryStore schema 参照。
     */
    val id: Long,
    val role: MessageRole,
    val text: String,
    @SerialName("chat_id") val chatId: String?,
)
