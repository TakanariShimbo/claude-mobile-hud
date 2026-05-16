package com.example.claudemobilehud.phone.data.model

import com.example.claudemobilehud.protocol.MessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone-local の会話 1 件分。`:protocol.ChatMessagePayload` を Phone 側で永続化する型として
 * 拡張し、画像添付 / chat_id / session_id を保持する。HistoryStore で JSON シリアライズされる。
 */
@Serializable
data class ChatMessage(
    /** Phone-local autoinc。HistoryStore でディスパッチ。Phase 3 §3.6 / `ChatMessagePayload.id`。 */
    val id: Long,
    val role: MessageRole,
    val text: String,
    /** Hub mint の chat_id (UUID v4)。Phone send 由来 / Hub reply 由来で一貫。 */
    @SerialName("chat_id") val chatId: String?,
    /** 確定 session_id (= claude code の `--session-id`)。未確定なら null。 */
    @SerialName("session_id") val sessionId: String?,
    @SerialName("created_at_ms") val createdAtMs: Long,
    /** 添付された画像。outgoing メッセージにのみ存在。 */
    val image: ImageAttachment? = null,
)
