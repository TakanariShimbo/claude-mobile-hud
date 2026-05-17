package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.PendingPermissionPayload

/** docs/03 §3.6.5.6: Hub outstanding と 1:1 / AD-13 snapshot で再構築 / toWirePayload で Glass 向け CXR へ写像。 */
@Immutable
data class PendingPermission(
    val requestId: String,
    val sessionId: String?,
    val toolName: String,
    val description: String,
    val inputPreview: String,
    val createdAtMs: Long,
) {
    fun toWirePayload(): PendingPermissionPayload = PendingPermissionPayload(
        requestId = requestId,
        toolName = toolName,
        description = description,
        inputPreview = inputPreview,
        sessionId = sessionId,
        createdAtMs = createdAtMs,
    )
}
