package com.example.claudemobilehud.glass.glass

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.MicSource
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.TranscriptState

/**
 * Glass-local の Phone 状態 model。Phase 3 §4.3。
 *
 * `CurrentState` wire payload を Glass-local 構造体に写したもの。
 * `pendingPermission` は wire の [PendingPermissionPayload] をそのまま再利用する。
 *
 * `@Immutable` をつけて Compose の skip 判定で常に同値比較が efficient になる
 * ようにする (Phone 側 PhoneUiState と同じ理由)。
 */
@Immutable
data class PhoneState(
    val mode: ConversationMode = ConversationMode.IDLE,
    val pendingPermission: PendingPermissionPayload? = null,
    val transcriptState: TranscriptState = TranscriptState.IDLE,
    val transcriptText: String = "",
    val inputText: String = "",
    val micSource: MicSource = MicSource.GLASS,
)

/**
 * Glass UI が通知時 banner として表示する一過性 event。
 * (CXR wire の `NotificationEvent` を持ち回すだけだが Compose 観点で `@Immutable`)。
 */
@Immutable
data class GlassNotification(
    val kind: com.example.claudemobilehud.protocol.NotificationKind,
    val text: String,
    val sessionId: String?,
)
