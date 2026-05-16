package com.example.claudemobilehud.phone.data.model

import com.example.claudemobilehud.protocol.ConversationMode

/**
 * Phone UI が観測する集約状態。Phase 3 §3.2.1。
 *
 * `ChannelRepository.uiState: StateFlow<PhoneUiState>` の中身。Compose の Screen は
 * これだけ collect すれば全表示状態を再構築できる。
 */
data class PhoneUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val pendingPermissions: List<PendingPermission> = emptyList(),
    val inputText: String = "",
    val attachedImage: ImageAttachment? = null,
    val mode: ConversationMode = ConversationMode.IDLE,
    val transcriptText: String = "",
    val connectivity: ConnectivityState = ConnectivityState.Idle,
)

/** session 一覧の 1 件分。`SessionStore` で管理。 */
data class SessionSummary(
    val id: String,
    val label: String,
    val messageCount: Int,
    val isActive: Boolean,
)
