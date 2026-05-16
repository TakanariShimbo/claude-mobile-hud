package com.example.claudemobilehud.phone.data

import app.cash.turbine.test
import com.example.claudemobilehud.phone.data.model.SseEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SessionStoreTest {

    @Test
    fun `applySseEvent Reply appends incoming and snapshot reflects current session`(
        @TempDir tmp: Path,
    ) = runTest {
        val store = SessionStore(HistoryStore(tmp.toFile()))
        store.applySseEvent(SseEvent.SessionActive("s1"))
        store.selectSession("s1")
        store.applySseEvent(SseEvent.Reply(chatId = "c1", sessionId = "s1", text = "hello"))

        val snap = store.snapshot.value
        assertEquals("s1", snap.currentSessionId)
        assertEquals(1, snap.messages.size)
        assertEquals("hello", snap.messages[0].text)
        assertEquals(1, snap.sessions.size)
        assertEquals(true, snap.sessions[0].isActive)
    }

    @Test
    fun `mergeUnknownSession promotes UNKNOWN messages to confirmed session`(
        @TempDir tmp: Path,
    ) = runTest {
        val store = SessionStore(HistoryStore(tmp.toFile()))
        // appendOutgoing with null sessionId → UNKNOWN bucket
        val outgoing = store.appendOutgoing(sessionId = null, text = "hi", chatId = "c1")
        assertEquals(null, outgoing.sessionId)
        // Reply arrives with confirmed session_id
        store.applySseEvent(SseEvent.Reply(chatId = "c1", sessionId = "S100", text = "reply"))

        val snap = store.snapshot.value
        val all = snap.sessions.flatMap { listOf(it.id) }
        assertEquals(listOf("S100"), all)
        store.selectSession("S100")
        val s100 = store.snapshot.value
        assertEquals(2, s100.messages.size)
        assertEquals(listOf("hi", "reply"), s100.messages.map { it.text })
    }

    @Test
    fun `restoreFromHistory loads messages from disk and continues id sequence`(
        @TempDir tmp: Path,
    ) = runTest {
        val history = HistoryStore(tmp.toFile())
        val store1 = SessionStore(history)
        store1.appendOutgoing(sessionId = "s1", text = "first", chatId = "c1")
        store1.appendOutgoing(sessionId = "s1", text = "second", chatId = "c2")
        store1.flush()

        val store2 = SessionStore(history)
        store2.restoreFromHistory()
        store2.selectSession("s1")
        val snap = store2.snapshot.value
        assertEquals(2, snap.messages.size)
        val third = store2.appendOutgoing(sessionId = "s1", text = "third", chatId = "c3")
        assertEquals(3L, third.id) // 既存 max id + 1
    }

    @Test
    fun `snapshot StateFlow emits on session selection`(@TempDir tmp: Path) = runTest {
        val store = SessionStore(HistoryStore(tmp.toFile()))
        store.snapshot.test {
            val initial = awaitItem()
            assertEquals(null, initial.currentSessionId)
            store.applySseEvent(SseEvent.SessionActive("s1"))
            // SessionActive で sessions が 1 件になるはず
            assertNotNull(awaitItem())
            store.selectSession("s1")
            val selected = awaitItem()
            assertEquals("s1", selected.currentSessionId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
