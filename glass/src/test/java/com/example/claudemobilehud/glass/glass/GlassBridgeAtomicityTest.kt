package com.example.claudemobilehud.glass.glass

import com.example.claudemobilehud.protocol.ChatMessagePayload
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.CurrentSessionEvent
import com.example.claudemobilehud.protocol.CurrentState
import com.example.claudemobilehud.protocol.ErrorEvent
import com.example.claudemobilehud.protocol.GestureEvent
import com.example.claudemobilehud.protocol.GestureKind
import com.example.claudemobilehud.protocol.Hello
import com.example.claudemobilehud.protocol.InputTextOnly
import com.example.claudemobilehud.protocol.ListeningCancel
import com.example.claudemobilehud.protocol.MessageRole
import com.example.claudemobilehud.protocol.MessagesEvent
import com.example.claudemobilehud.protocol.MicSource
import com.example.claudemobilehud.protocol.NotificationEvent
import com.example.claudemobilehud.protocol.NotificationKind
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.PermissionVerdictEvent
import com.example.claudemobilehud.protocol.Ping
import com.example.claudemobilehud.protocol.SelectSession
import com.example.claudemobilehud.protocol.SessionClose
import com.example.claudemobilehud.protocol.SessionList
import com.example.claudemobilehud.protocol.SessionOpen
import com.example.claudemobilehud.protocol.SessionSummaryPayload
import com.example.claudemobilehud.protocol.TranscriptState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 3 §4.2.1 の atomicity / stale ドロップ仕様を JVM 単体テストする。
 *
 * `GlassBridge` は `object` (process singleton) なので test 間で state を共有しない
 * よう `resetStateForTest()` を `@BeforeEach` で呼ぶ。
 */
class GlassBridgeAtomicityTest {

    @BeforeEach
    fun reset() {
        GlassBridge.resetStateForTest()
    }

    @Test
    fun `SessionOpen sets sessionOpen and resets seq tracking`() {
        // 先に CurrentState を 1 件入れて lastSeen を進めておく。
        GlassBridge.handleWireEvent(currentState(seq = 5))
        assertEquals(5 to null, GlassBridge.atomicityStateForTest())

        GlassBridge.handleWireEvent(SessionOpen(ts = 1L))

        // sessionOpen が立ち、seq/pending が両方リセットされる。
        assertEquals(true, GlassBridge.sessionOpen.value)
        assertEquals(0 to null, GlassBridge.atomicityStateForTest())
    }

    @Test
    fun `CurrentState updates phoneState and advances seq`() {
        GlassBridge.handleWireEvent(currentState(seq = 3, inputText = "hello"))
        val state = GlassBridge.phoneState.value
        assertEquals(ConversationMode.IDLE, state.mode)
        assertEquals("hello", state.inputText)
        assertEquals(3, GlassBridge.atomicityStateForTest().first)
    }

    @Test
    fun `Stale CurrentState (seq lower or equal) is dropped`() {
        GlassBridge.handleWireEvent(currentState(seq = 5, inputText = "fresh"))
        // 同じ seq は drop。
        GlassBridge.handleWireEvent(currentState(seq = 5, inputText = "should_not_apply"))
        // 古い seq も drop。
        GlassBridge.handleWireEvent(currentState(seq = 4, inputText = "older"))
        assertEquals("fresh", GlassBridge.phoneState.value.inputText)
        assertEquals(5, GlassBridge.atomicityStateForTest().first)
    }

    @Test
    fun `InputTextOnly with parent_seq matching lastSeen swaps inputText only`() {
        GlassBridge.handleWireEvent(currentState(seq = 7, inputText = "orig"))
        // parent_seq == lastSeen → 即 apply。
        GlassBridge.handleWireEvent(InputTextOnly(parentSeq = 7, inputText = "patched", ts = 1L))
        val state = GlassBridge.phoneState.value
        assertEquals("patched", state.inputText)
        // mode 等は変わっていない。
        assertEquals(ConversationMode.IDLE, state.mode)
        // lastSeen は変えない (parent_seq 一致は seq の進行ではない)。
        assertEquals(7, GlassBridge.atomicityStateForTest().first)
    }

    @Test
    fun `InputTextOnly with older parent_seq is dropped`() {
        GlassBridge.handleWireEvent(currentState(seq = 10, inputText = "stay"))
        GlassBridge.handleWireEvent(InputTextOnly(parentSeq = 9, inputText = "outdated", ts = 1L))
        assertEquals("stay", GlassBridge.phoneState.value.inputText)
        // pending には積まれない。
        assertNull(GlassBridge.atomicityStateForTest().second)
    }

    @Test
    fun `InputTextOnly arriving before its CurrentState is stashed and applied later`() {
        // 1) parent_seq=8 の InputTextOnly が先着 → stash。
        GlassBridge.handleWireEvent(InputTextOnly(parentSeq = 8, inputText = "from_stash", ts = 1L))
        assertEquals(0 to (8 to "from_stash"), GlassBridge.atomicityStateForTest())
        // 元の phoneState は触られていない。
        assertEquals("", GlassBridge.phoneState.value.inputText)

        // 2) seq=8 の CurrentState 到着 → stash を消費して inputText を上書き。
        GlassBridge.handleWireEvent(currentState(seq = 8, inputText = "wire_value"))
        assertEquals("from_stash", GlassBridge.phoneState.value.inputText)
        // stash は消費されて null に戻る。
        assertNull(GlassBridge.atomicityStateForTest().second)
    }

    @Test
    fun `Stashed InputTextOnly is cleared when CurrentState seq advances past it (P2-E)`() {
        // parent_seq=4 stash → 次に来た CurrentState seq=5 は別物。stash は反映しない上、
        // P2-E of 5a review で `pending.parentSeq <= 新 seq` の場合は即破棄する仕様に変更。
        // (旧来は永続して "ghost" が singleton state に残る hazard があった)
        GlassBridge.handleWireEvent(InputTextOnly(parentSeq = 4, inputText = "ghost", ts = 1L))
        GlassBridge.handleWireEvent(currentState(seq = 5, inputText = "actual"))
        assertEquals("actual", GlassBridge.phoneState.value.inputText)
        val (lastSeen, pending) = GlassBridge.atomicityStateForTest()
        assertEquals(5, lastSeen)
        assertNull(pending)
    }

    @Test
    fun `Stashed InputTextOnly with parentSeq above new seq survives until matched (P2-E)`() {
        // 既に lastSeen=2 で pending=(7, "future")。CurrentState seq=3 が来たら lastSeen=3
        // に進むが pending.parentSeq=7 > 3 のため pending は survive する。
        GlassBridge.handleWireEvent(currentState(seq = 2, inputText = "v2"))
        GlassBridge.handleWireEvent(InputTextOnly(parentSeq = 7, inputText = "future", ts = 1L))
        GlassBridge.handleWireEvent(currentState(seq = 3, inputText = "v3"))
        val (lastSeen, pending) = GlassBridge.atomicityStateForTest()
        assertEquals(3, lastSeen)
        assertEquals(7 to "future", pending)
        // 続いて seq=7 が来たら pending の text が適用される。
        GlassBridge.handleWireEvent(currentState(seq = 7, inputText = "wire7"))
        assertEquals("future", GlassBridge.phoneState.value.inputText)
        assertNull(GlassBridge.atomicityStateForTest().second)
    }

    @Test
    fun `SessionList event updates sessions StateFlow`() {
        GlassBridge.handleWireEvent(
            SessionList(
                sessions = listOf(
                    SessionSummaryPayload(id = "s1", label = "s1-label", messageCount = 3),
                ),
                ts = 1L,
            ),
        )
        assertEquals(1, GlassBridge.sessions.value.size)
        assertEquals("s1", GlassBridge.sessions.value[0].id)
    }

    @Test
    fun `CurrentSessionEvent with id sets currentSessionId, null clears it`() {
        GlassBridge.handleWireEvent(CurrentSessionEvent(id = "abc", ts = 1L))
        assertEquals("abc", GlassBridge.currentSessionId.value)
        GlassBridge.handleWireEvent(CurrentSessionEvent(id = null, ts = 2L))
        assertNull(GlassBridge.currentSessionId.value)
    }

    @Test
    fun `MessagesEvent replaces messages StateFlow`() {
        GlassBridge.handleWireEvent(
            MessagesEvent(
                sessionId = "s1",
                messages = listOf(
                    ChatMessagePayload(id = 1L, role = MessageRole.INCOMING, text = "hi", chatId = "c1"),
                ),
                ts = 1L,
            ),
        )
        assertEquals(1, GlassBridge.messages.value.size)
    }

    @Test
    fun `ErrorEvent stores last error message`() {
        GlassBridge.handleWireEvent(ErrorEvent(message = "boom", ts = 1L))
        assertEquals("boom", GlassBridge.lastError.value)
    }

    @Test
    fun `NotificationEvent does not crash without subscribers (DROP_OLDEST buffer)`() {
        // SharedFlow に subscribe しない状態でも emit が落ちないこと。buffer=8 で DROP_OLDEST。
        repeat(20) { i ->
            GlassBridge.handleWireEvent(
                NotificationEvent(
                    kind = NotificationKind.REPLY,
                    text = "n$i",
                    sessionId = "s",
                    ts = i.toLong(),
                ),
            )
        }
        // 落ちなければ OK (アサーション無し)。状態側に副作用ないので phoneState は初期値のまま。
        assertEquals("", GlassBridge.phoneState.value.inputText)
    }

    @Test
    fun `SessionClose flips sessionOpen back to false`() {
        GlassBridge.handleWireEvent(SessionOpen(ts = 1L))
        assertEquals(true, GlassBridge.sessionOpen.value)
        GlassBridge.handleWireEvent(SessionClose(ts = 2L))
        assertEquals(false, GlassBridge.sessionOpen.value)
    }

    @Test
    fun `SessionClose preserves phoneState and lastSeenStateSeq (P2-G)`() {
        // SessionClose は sessionOpen のみ落とし、phoneState / seq tracking はそのまま
        // (Phase 2 §4.5.3 B-1 で SessionOpen に reset 役を集約する設計)。
        GlassBridge.handleWireEvent(currentState(seq = 5, inputText = "keep"))
        GlassBridge.handleWireEvent(SessionClose(ts = 1L))
        assertEquals("keep", GlassBridge.phoneState.value.inputText)
        assertEquals(5, GlassBridge.atomicityStateForTest().first)
    }

    @Test
    fun `Ping marks sessionOpen and arms timeout`() {
        // Ping は heartbeat 用 (5s 周期)。sessionOpen を true にし、timeout を再 arm する。
        GlassBridge.handleWireEvent(Ping(ts = 1L))
        assertEquals(true, GlassBridge.sessionOpen.value)
    }

    @Test
    fun `Glass-to-Phone events are no-op when received (P2-G)`() {
        // Hello / SelectSession / GestureEvent / ListeningCancel / PermissionVerdictEvent は
        // Glass→Phone 方向の wire 型なので、Glass 側受信経路では現れないはずだが、
        // exhaustive when の no-op 枝が crash しないことを保証する。
        GlassBridge.handleWireEvent(currentState(seq = 1, inputText = "anchor"))
        val baseline = GlassBridge.phoneState.value
        GlassBridge.handleWireEvent(Hello(ts = 2L))
        GlassBridge.handleWireEvent(SelectSession(id = "x", ts = 3L))
        GlassBridge.handleWireEvent(GestureEvent(which = GestureKind.TAP, ts = 4L))
        GlassBridge.handleWireEvent(ListeningCancel(ts = 5L))
        GlassBridge.handleWireEvent(
            PermissionVerdictEvent(requestId = "r1", decision = PermissionDecision.ALLOW, ts = 6L),
        )
        // 副作用無し: phoneState はそのまま。
        assertEquals(baseline, GlassBridge.phoneState.value)
    }

    @Test
    fun `pendingPermission and transcript fields flow into phoneState`() {
        val perm = PendingPermissionPayload(
            requestId = "r1",
            toolName = "Bash",
            description = "ls",
            inputPreview = "ls -la",
            sessionId = "s1",
            createdAtMs = 100L,
        )
        GlassBridge.handleWireEvent(
            currentState(
                seq = 1,
                mode = ConversationMode.PERMISSION_CONFIRMING,
                pendingPermission = perm,
                transcriptState = TranscriptState.LISTENING,
                transcriptText = "partial",
            ),
        )
        val state = GlassBridge.phoneState.value
        assertEquals(ConversationMode.PERMISSION_CONFIRMING, state.mode)
        assertEquals("r1", state.pendingPermission?.requestId)
        assertEquals(TranscriptState.LISTENING, state.transcriptState)
        assertEquals("partial", state.transcriptText)
    }

    private fun currentState(
        seq: Int,
        mode: ConversationMode = ConversationMode.IDLE,
        pendingPermission: PendingPermissionPayload? = null,
        transcriptState: TranscriptState = TranscriptState.IDLE,
        transcriptText: String = "",
        inputText: String = "",
        micSource: MicSource = MicSource.GLASS,
        ts: Long = seq.toLong(),
    ) = CurrentState(
        seq = seq,
        mode = mode,
        pendingPermission = pendingPermission,
        transcriptState = transcriptState,
        transcriptText = transcriptText,
        inputText = inputText,
        micSource = micSource,
        ts = ts,
    )
}
