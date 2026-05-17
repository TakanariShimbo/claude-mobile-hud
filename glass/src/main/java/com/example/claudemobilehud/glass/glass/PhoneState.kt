package com.example.claudemobilehud.glass.glass

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.MicSource
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.TranscriptState

/** docs/03 §4.3 / §4.3.1: CurrentState wire を Glass-local 化。pendingPermission は wire 型を直接再利用。 */
@Immutable
data class PhoneState(
    val mode: ConversationMode = ConversationMode.IDLE,
    val pendingPermission: PendingPermissionPayload? = null,
    val transcriptState: TranscriptState = TranscriptState.IDLE,
    val transcriptText: String = "",
    val inputText: String = "",
    val micSource: MicSource = MicSource.GLASS,
)

/** docs/03 §4.3.2: 一過性 banner 用の薄い @Immutable wrapper。 */
@Immutable
data class GlassNotification(
    val kind: com.example.claudemobilehud.protocol.NotificationKind,
    val text: String,
    val sessionId: String?,
)
