package com.example.claudemobilehud.phone.data.model

/**
 * Hub から SSE で来るイベントを Phone-local の sealed class で表現。Phase 2 §4.3.1。
 *
 * 接続ライフサイクル (`Open` / `Failure` / `AuthFailed`) と wire イベントを 1 つの
 * sealed で扱うことで、ConnectionController と SessionStore が同じ flow を購読できる。
 *
 * Wire との対応 (event type → sealed):
 *   reply              → Reply
 *   permission         → Permission
 *   permission_abort   → PermissionAbort
 *   session_active     → SessionActive
 *   session_inactive   → SessionInactive
 *   session_snapshot   → SessionSnapshot
 *   permission_snapshot → PermissionSnapshot
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
