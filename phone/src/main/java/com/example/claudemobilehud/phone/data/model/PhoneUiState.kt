package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.ConversationMode

/**
 * Phone UI が観測する集約状態。Phase 3 §3.2.1。
 *
 * `ChannelRepository.uiState: StateFlow<PhoneUiState>` の中身。Compose の Screen は
 * これだけ collect すれば全表示状態を再構築できる。
 *
 * P3-A of 4c2 review: `@Immutable` で Compose の skippable 解析にヒントを与え、
 * 親の recompose 時に同値引数なら子の recompose を skip できるようにする
 * (`List<...>` は Compose 上 stable 判定が緩いため、コンテナ側で明示する)。
 */
@Immutable
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
@Immutable
data class SessionSummary(
    val id: String,
    val label: String,
    val messageCount: Int,
    val isActive: Boolean,
)
