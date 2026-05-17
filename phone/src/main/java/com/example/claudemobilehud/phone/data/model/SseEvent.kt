package com.example.claudemobilehud.phone.data.model

/**
 * Hub からの SSE と接続ライフサイクルを 1 つの sealed に統合した Phone-local モデル
 * (docs/03 §3.2.2.6)。wire event type → sealed のマッピング表も §3.2.2.6 を参照。
 */
sealed class SseEvent {
    // --- 接続層由来 (Hub からの wire ではない) ---
    data object Open : SseEvent()
    data class Failure(val message: String) : SseEvent()
    data object AuthFailed : SseEvent()
    data object Closed : SseEvent()

    // --- wire イベント ---
    data class Reply(
        val chatId: String,
        val sessionId: String?,
        val text: String,
    ) : SseEvent()

    data class Permission(
        val requestId: String,
        val sessionId: String?,
        val toolName: String,
        val description: String,
        val inputPreview: String,
    ) : SseEvent()

    data class PermissionAbort(
        val requestId: String,
        val reason: String?,
    ) : SseEvent()

    data class SessionActive(val sessionId: String) : SseEvent()
    data class SessionInactive(val sessionId: String) : SseEvent()
    data class SessionSnapshot(val activeSessionIds: List<String>) : SseEvent()
    data class PermissionSnapshot(val requestIds: List<String>) : SseEvent()
}
