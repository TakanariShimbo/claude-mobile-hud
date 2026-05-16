package com.example.claudemobilehud.glass.ui.conversation

import app.cash.turbine.test
import com.example.claudemobilehud.glass.gesture.GlassGesture
import com.example.claudemobilehud.glass.glass.PhoneState
import com.example.claudemobilehud.protocol.ConversationMode
import com.example.claudemobilehud.protocol.GestureKind
import com.example.claudemobilehud.protocol.PendingPermissionPayload
import com.example.claudemobilehud.protocol.PermissionDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ConversationStateHolder` の state machine + gesture dispatch を testable Sender 越しに
 * 検証する (Phase 3 §4.4 / FR-GL-30〜62)。
 *
 * GlassBridge は内部で参照されるが、ここでは [RecordingSender] を渡して wire 送信を
 * 横取りする。phoneState は MutableStateFlow を渡して任意の mode 遷移を再現できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationStateHolderTest {

    private class RecordingSender : ConversationStateHolder.Sender {
        val gestures = mutableListOf<GestureKind>()
        var listeningCancelCount = 0
        val verdicts = mutableListOf<Pair<String, PermissionDecision>>()
        override fun sendGesture(which: GestureKind) {
            gestures += which
        }
        override fun sendListeningCancel() {
            listeningCancelCount++
        }
        override fun sendPermissionVerdict(requestId: String, decision: PermissionDecision) {
            verdicts += requestId to decision
        }
    }

    private fun rig(initial: PhoneState = PhoneState()): Rig {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val phoneState = MutableStateFlow(initial)
        val sender = RecordingSender()
        var backInvoked = 0
        val holder = ConversationStateHolder(
            phoneState = phoneState,
            onBack = { backInvoked++ },
            scope = scope,
            bridge = sender,
        )
        return Rig(scope, phoneState, holder, sender) { backInvoked }
    }

    private class Rig(
        val scope: TestScope,
        val phoneState: MutableStateFlow<PhoneState>,
        val holder: ConversationStateHolder,
        val sender: RecordingSender,
        val backInvoked: () -> Int,
    )

    @Test
    fun `initial state is Idle`() = runTest {
        val r = rig()
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(ConversationStateHolder.State.Idle, r.holder.state.value)
    }

    @Test
    fun `mode LISTENING maps to Listening state`() = runTest {
        val r = rig()
        r.phoneState.value = PhoneState(mode = ConversationMode.LISTENING)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(ConversationStateHolder.State.Listening, r.holder.state.value)
    }

    @Test
    fun `mode CONFIRMING maps to Confirming with default SEND choice`() = runTest {
        val r = rig()
        r.phoneState.value = PhoneState(mode = ConversationMode.CONFIRMING)
        r.scope.testScheduler.advanceUntilIdle()
        val s = r.holder.state.value
        assertTrue(s is ConversationStateHolder.State.Confirming)
        assertEquals(ConversationStateHolder.SendChoice.SEND, (s as ConversationStateHolder.State.Confirming).choice)
    }

    @Test
    fun `Listening to Confirming resets sendChoice back to SEND`() = runTest {
        val r = rig()
        // Confirming に入って CANCEL を選ぶ。
        r.phoneState.value = PhoneState(mode = ConversationMode.CONFIRMING)
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.SwipeForward) // SEND → CANCEL
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.SendChoice.CANCEL,
            (r.holder.state.value as ConversationStateHolder.State.Confirming).choice,
        )
        // Listening → Confirming への遷移で SEND にリセットされる仕様。
        r.phoneState.value = PhoneState(mode = ConversationMode.LISTENING)
        r.scope.testScheduler.advanceUntilIdle()
        r.phoneState.value = PhoneState(mode = ConversationMode.CONFIRMING)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.SendChoice.SEND,
            (r.holder.state.value as ConversationStateHolder.State.Confirming).choice,
        )
    }

    @Test
    fun `permission requestId change resets permissionChoice to ALLOW`() = runTest {
        val r = rig()
        val perm1 = PendingPermissionPayload(
            requestId = "r1", toolName = "Bash", description = "ls",
            inputPreview = "ls", sessionId = "s1", createdAtMs = 100L,
        )
        r.phoneState.value = PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm1)
        r.scope.testScheduler.advanceUntilIdle()
        // 切替えて DENY 側にする。
        r.holder.onGesture(GlassGesture.SwipeForward)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.PermissionChoice.DENY,
            (r.holder.state.value as ConversationStateHolder.State.PermissionConfirming).choice,
        )
        // 新 permission (別 requestId) に切替えると ALLOW にリセット。
        val perm2 = perm1.copy(requestId = "r2", description = "rm")
        r.phoneState.value = PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm2)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.PermissionChoice.ALLOW,
            (r.holder.state.value as ConversationStateHolder.State.PermissionConfirming).choice,
        )
    }

    @Test
    fun `PERMISSION_CONFIRMING with null pending falls back to Idle`() = runTest {
        val r = rig()
        r.phoneState.value = PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = null)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(ConversationStateHolder.State.Idle, r.holder.state.value)
    }

    @Test
    fun `Idle Tap sends TAP gesture`() = runTest {
        val r = rig()
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf(GestureKind.TAP), r.sender.gestures)
    }

    @Test
    fun `Idle DoubleTap sends DOUBLE_TAP and calls onBack`() = runTest {
        val r = rig()
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.DoubleTap)
        assertEquals(listOf(GestureKind.DOUBLE_TAP), r.sender.gestures)
        assertEquals(1, r.backInvoked())
    }

    @Test
    fun `Idle swipes emit scrollRequest without wire send`() = runTest {
        val r = rig()
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.scrollRequest.test {
            r.holder.onGesture(GlassGesture.SwipeForward)
            assertEquals(3, awaitItem())
            r.holder.onGesture(GlassGesture.SwipeBack)
            assertEquals(-3, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(r.sender.gestures.isEmpty())
    }

    @Test
    fun `Listening DoubleTap sends ListeningCancel (= cancel recording, P1-C)`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.LISTENING))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.DoubleTap)
        // 旧 TAP + SWIPE_BACK 2 連 → 専用 wire ListeningCancel 1 件に変更 (P1-C of 5b review)。
        assertTrue(r.sender.gestures.isEmpty())
        assertEquals(1, r.sender.listeningCancelCount)
        assertEquals(0, r.backInvoked()) // 会話画面には残る
    }

    @Test
    fun `Listening Tap sends TAP gesture only (P2-E)`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.LISTENING))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf(GestureKind.TAP), r.sender.gestures)
        assertEquals(0, r.sender.listeningCancelCount)
    }

    @Test
    fun `Listening swipe scrolls without wire send (P2-E)`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.LISTENING))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.scrollRequest.test {
            r.holder.onGesture(GlassGesture.SwipeForward)
            assertEquals(3, awaitItem())
            r.holder.onGesture(GlassGesture.SwipeBack)
            assertEquals(-3, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(r.sender.gestures.isEmpty())
        assertEquals(0, r.sender.listeningCancelCount)
    }

    @Test
    fun `PermissionConfirming to Confirming preserves sendChoice (P2-E)`() = runTest {
        // Confirming で CANCEL を選んだあと PermissionConfirming に割り込まれ、復帰した
        // ときに CANCEL のままであることを確認 (Listening→Confirming だけが reset 対象)。
        val perm = PendingPermissionPayload(
            requestId = "rA", toolName = "Bash", description = "ls",
            inputPreview = "ls", sessionId = "s1", createdAtMs = 100L,
        )
        val r = rig()
        r.phoneState.value = PhoneState(mode = ConversationMode.CONFIRMING)
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.SwipeForward) // SEND → CANCEL
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.SendChoice.CANCEL,
            (r.holder.state.value as ConversationStateHolder.State.Confirming).choice,
        )
        // 割り込み: PermissionConfirming に遷移。
        r.phoneState.value = PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm)
        r.scope.testScheduler.advanceUntilIdle()
        // 復帰: Confirming に戻る。CANCEL が preserve されていること。
        r.phoneState.value = PhoneState(mode = ConversationMode.CONFIRMING)
        r.scope.testScheduler.advanceUntilIdle()
        assertEquals(
            ConversationStateHolder.SendChoice.CANCEL,
            (r.holder.state.value as ConversationStateHolder.State.Confirming).choice,
        )
    }

    @Test
    fun `Atomic PhoneState transition does not flicker through fallback Idle (P1-A)`() = runTest {
        // PhoneState 1 emit で (IDLE,null) → (PERMISSION_CONFIRMING,perm) に原子的に遷移
        // させて、中間の "PERMISSION_CONFIRMING + pending=null → Idle" 状態が出ないことを
        // 確認する。state 全変遷を list に貯めて、観測列が [Idle, PermissionConfirming] の
        // 2 件だけになることを assert する (= 中間 Idle fallback が無い)。
        val r = rig()
        val observed = mutableListOf<ConversationStateHolder.State>()
        val collectJob = r.scope.launch {
            r.holder.state.collect { observed += it }
        }
        r.scope.testScheduler.advanceUntilIdle()
        val perm = PendingPermissionPayload(
            requestId = "atom", toolName = "Bash", description = "x",
            inputPreview = "x", sessionId = "s", createdAtMs = 1L,
        )
        // 1 emit で mode と pending を同時にセット。
        r.phoneState.value = PhoneState(
            mode = ConversationMode.PERMISSION_CONFIRMING,
            pendingPermission = perm,
        )
        r.scope.testScheduler.advanceUntilIdle()
        collectJob.cancel()

        // 観測: Idle (initial) → PermissionConfirming のみ。間に Idle fallback が
        // 挟まっていない (旧 combine 2 派生実装ではここに Idle が 1 件挟まっていた)。
        assertEquals(2, observed.size)
        assertEquals(ConversationStateHolder.State.Idle, observed[0])
        assertTrue(observed[1] is ConversationStateHolder.State.PermissionConfirming)
    }

    @Test
    fun `Confirming Tap sends SWIPE_FORWARD when choice is SEND`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.CONFIRMING))
        r.scope.testScheduler.advanceUntilIdle()
        // 初期 SEND のまま決定。
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf(GestureKind.SWIPE_FORWARD), r.sender.gestures)
    }

    @Test
    fun `Confirming swipe toggles SEND CANCEL and Tap sends SWIPE_BACK when CANCEL`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.CONFIRMING))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.SwipeForward)
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf(GestureKind.SWIPE_BACK), r.sender.gestures)
    }

    @Test
    fun `Confirming DoubleTap sends SWIPE_BACK (= cancel send)`() = runTest {
        val r = rig(PhoneState(mode = ConversationMode.CONFIRMING))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.DoubleTap)
        assertEquals(listOf(GestureKind.SWIPE_BACK), r.sender.gestures)
        assertEquals(0, r.backInvoked()) // back は呼ばない
    }

    @Test
    fun `PermissionConfirming Tap sends verdict with current choice`() = runTest {
        val perm = PendingPermissionPayload(
            requestId = "rX", toolName = "Bash", description = "ls",
            inputPreview = "ls", sessionId = "s1", createdAtMs = 100L,
        )
        val r = rig(PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm))
        r.scope.testScheduler.advanceUntilIdle()
        // 初期 ALLOW で決定。
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf("rX" to PermissionDecision.ALLOW), r.sender.verdicts)
    }

    @Test
    fun `PermissionConfirming DoubleTap always denies (safe default)`() = runTest {
        val perm = PendingPermissionPayload(
            requestId = "rY", toolName = "Bash", description = "ls",
            inputPreview = "ls", sessionId = "s1", createdAtMs = 100L,
        )
        val r = rig(PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm))
        r.scope.testScheduler.advanceUntilIdle()
        // ALLOW のままダブルタップ → DENY が送られる (FR-GL-62)。
        r.holder.onGesture(GlassGesture.DoubleTap)
        assertEquals(listOf("rY" to PermissionDecision.DENY), r.sender.verdicts)
    }

    @Test
    fun `PermissionConfirming swipe toggles ALLOW DENY`() = runTest {
        val perm = PendingPermissionPayload(
            requestId = "rZ", toolName = "Bash", description = "ls",
            inputPreview = "ls", sessionId = "s1", createdAtMs = 100L,
        )
        val r = rig(PhoneState(mode = ConversationMode.PERMISSION_CONFIRMING, pendingPermission = perm))
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.SwipeForward)
        r.scope.testScheduler.advanceUntilIdle()
        r.holder.onGesture(GlassGesture.Tap)
        assertEquals(listOf("rZ" to PermissionDecision.DENY), r.sender.verdicts)
    }
}
