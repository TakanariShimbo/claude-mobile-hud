package com.example.claudemobilehud.protocol

/**
 * 全 WireEvent ケースのカノニカル sample 集。
 * - JsonCodec round-trip テスト (`JsonCodecTest`)
 * - golden 生成 (`KotlinGoldenGeneratorTest`)
 * の両方が同じ sample を参照することで、parity を担保する。
 */
internal object WireSamples {
    val all: List<Pair<String, WireEvent>> = listOf(
        "current_state" to CurrentState(
            seq = 42,
            mode = ConversationMode.PERMISSION_CONFIRMING,
            pendingPermission = PendingPermissionPayload(
                requestId = "req-001",
                toolName = "Bash",
                description = "Run rm -rf /tmp/cache",
                inputPreview = "rm -rf /tmp/cache",
                sessionId = "sess-1",
                createdAtMs = 1_700_000_000_000L,
            ),
            transcriptState = TranscriptState.LISTENING,
            transcriptText = "Hello world",
            inputText = "What is foo?",
            micSource = MicSource.GLASS,
            ts = 1_700_000_000_500L,
        ),
        "input_text_only" to InputTextOnly(
            parentSeq = 42,
            inputText = "edited text",
            ts = 1_700_000_001_000L,
        ),
        "session_list" to SessionList(
            sessions = listOf(
                SessionSummaryPayload(id = "s1", label = "Session 1", messageCount = 5),
                SessionSummaryPayload(id = "s2", label = "Session 2", messageCount = 0),
            ),
            ts = 1_700_000_002_000L,
        ),
        "current_session_set" to CurrentSessionEvent(
            id = "s1",
            ts = 1_700_000_003_000L,
        ),
        "current_session_clear" to CurrentSessionEvent(
            id = null,
            ts = 1_700_000_003_500L,
        ),
        "messages" to MessagesEvent(
            sessionId = "s1",
            messages = listOf(
                ChatMessagePayload(id = 1, role = MessageRole.OUTGOING, text = "hi", chatId = "c1"),
                ChatMessagePayload(id = 2, role = MessageRole.INCOMING, text = "hello", chatId = "c1"),
                ChatMessagePayload(id = 3, role = MessageRole.SYSTEM, text = "system note", chatId = null),
            ),
            ts = 1_700_000_004_000L,
        ),
        "notification_reply" to NotificationEvent(
            kind = NotificationKind.REPLY,
            text = "reply received",
            sessionId = "s1",
            ts = 1_700_000_005_000L,
        ),
        "notification_permission" to NotificationEvent(
            kind = NotificationKind.PERMISSION,
            text = "permission requested",
            sessionId = null,
            ts = 1_700_000_005_500L,
        ),
        "error" to ErrorEvent(
            message = "something failed",
            ts = 1_700_000_006_000L,
        ),
        "hello" to Hello(ts = 1_700_000_007_000L),
        "select_session" to SelectSession(id = "s2", ts = 1_700_000_008_000L),
        "gesture_tap" to GestureEvent(which = GestureKind.TAP, ts = 1_700_000_009_000L),
        "gesture_double_tap" to GestureEvent(which = GestureKind.DOUBLE_TAP, ts = 1_700_000_009_100L),
        "gesture_swipe_forward" to GestureEvent(which = GestureKind.SWIPE_FORWARD, ts = 1_700_000_009_200L),
        "gesture_swipe_back" to GestureEvent(which = GestureKind.SWIPE_BACK, ts = 1_700_000_009_300L),
        "listening_cancel" to ListeningCancel(ts = 1_700_000_010_000L),
        "permission_verdict_allow" to PermissionVerdictEvent(
            requestId = "req-001",
            decision = PermissionDecision.ALLOW,
            ts = 1_700_000_011_000L,
        ),
        "permission_verdict_deny" to PermissionVerdictEvent(
            requestId = "req-002",
            decision = PermissionDecision.DENY,
            ts = 1_700_000_011_500L,
        ),
        "session_open" to SessionOpen(ts = 1_700_000_012_000L),
        "session_close" to SessionClose(ts = 1_700_000_012_500L),
        "ping" to Ping(ts = 1_700_000_013_000L),
    )
}
