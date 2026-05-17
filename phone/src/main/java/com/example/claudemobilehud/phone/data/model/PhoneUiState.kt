package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.ConversationMode

/**
 * Phone UI が観測する集約 immutable state (docs/03 §3.2.1.5)。
 * pendingForCurrent と pendingPermissions の分離は NFR-51、@Immutable 配置は P3-A を参照。
 */
@Immutable
data class PhoneUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val pendingForCurrent: List<PendingPermission> = emptyList(),
    val inputText: String = "",
    val attachedImage: ImageAttachment? = null,
    val mode: ConversationMode = ConversationMode.IDLE,
    val transcriptText: String = "",
    val connectivity: ConnectivityState = ConnectivityState.Idle,
)

@Immutable
data class SessionSummary(
    val id: String,
    val label: String,
    val messageCount: Int,
    val isActive: Boolean,
)
