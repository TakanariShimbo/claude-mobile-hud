package com.example.claudemobilehud.phone.data.model

import androidx.compose.runtime.Immutable
import com.example.claudemobilehud.protocol.PendingPermissionPayload

/**
 * Phone-local の保留中 permission。Hub の outstanding と一対一対応。Phase 3 §3.2。
 * 受信した SSE event をそのまま data 化したもの。AD-13 で SSE 再接続時に
 * permission_snapshot を受けて再構築される。
 *
 * P3-A of 4c2 review: `@Immutable` で PermissionDialog の引数 stability を保つ。
 */
@Immutable
data class PendingPermission(
    val requestId: String,
    val sessionId: String?,
    val toolName: String,
    val description: String,
    val inputPreview: String,
    val createdAtMs: Long,
) {
    /** Glass 向けに送る wire payload (`:protocol.PendingPermissionPayload`) へ写像。 */
    fun toWirePayload(): PendingPermissionPayload = PendingPermissionPayload(
        requestId = requestId,
        toolName = toolName,
        description = description,
        inputPreview = inputPreview,
        sessionId = sessionId,
        createdAtMs = createdAtMs,
    )
}
