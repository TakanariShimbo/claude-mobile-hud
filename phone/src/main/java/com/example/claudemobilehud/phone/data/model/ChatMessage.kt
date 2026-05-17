package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.MessageRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** docs/03 §3.6.5.2: ChatMessagePayload を永続化向けに拡張 (id / chatId / sessionId / createdAt / image)。 */
@Immutable
@Serializable
data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    @SerialName("chat_id") val chatId: String?,
    @SerialName("session_id") val sessionId: String?,
    @SerialName("created_at_ms") val createdAtMs: Long,
    val image: ImageAttachment? = null,
)
