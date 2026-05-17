package com.example.claudemobilehud.phone.glass

import com.example.claudemobilehud.phone.data.model.ChatMessage
import com.example.claudemobilehud.phone.data.model.PhoneUiState
import com.example.claudemobilehud.phone.data.model.SessionSummary
import com.example.claudemobilehud.protocol.MessageRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GlassRelay の payload mapping だけを純関数として test する (#179)。
 * Service / Repository の起動を必要としないので、`MutableStateFlow` の
 * collect 経路を flake させずに mapping 仕様を pin できる。
 *
 * 設計判断: GlassRelay 本体 (suspend collect chain) は `GlassConnectionService.sender`
 * (object singleton) を見るため unit test しにくい。本書 §3.4.1 の「`uiState` →
 * `sessionListForWire` / `messagesForWire`」mapping を internal extension に
 * 抽出して、ここで mapping 仕様を押さえる。
 */
class GlassRelayMappingTest {

    @Test
    fun `sessionListForWire emits only active sessions`() {
        val state = PhoneUiState(
            sessions = listOf(
                SessionSummary(id = "s1", label = "s1", messageCount = 0, isActive = true),
                SessionSummary(id = "s2", label = "s2", messageCount = 3, isActive = false),
                SessionSummary(id = "s3", label = "s3", messageCount = 1, isActive = true),
            ),
        )

        val payloads = state.sessionListForWire()

        assertEquals(2, payloads.size, "inactive (s2) は除外される")
        assertEquals("s1", payloads[0].id)
        assertEquals("s3", payloads[1].id)
    }

    @Test
    fun `sessionListForWire returns empty list when no session is active`() {
        val state = PhoneUiState(
            sessions = listOf(
                SessionSummary(id = "s1", label = "s1", messageCount = 0, isActive = false),
                SessionSummary(id = "s2", label = "s2", messageCount = 0, isActive = false),
            ),
        )

        assertTrue(state.sessionListForWire().isEmpty())
    }

    @Test
    fun `sessionListForWire preserves label and messageCount`() {
        val state = PhoneUiState(
            sessions = listOf(
                SessionSummary(id = "s1", label = "fooLabel", messageCount = 42, isActive = true),
            ),
        )

        val p = state.sessionListForWire().single()
        assertEquals("s1", p.id)
        assertEquals("fooLabel", p.label)
        assertEquals(42, p.messageCount)
    }

    @Test
    fun `messagesForWire pairs currentSessionId with mapped payloads`() {
        val state = PhoneUiState(
            currentSessionId = "s1",
            messages = listOf(
                ChatMessage(
                    id = 1L,
                    role = MessageRole.OUTGOING,
                    text = "hello",
                    chatId = "c-1",
                    sessionId = "s1",
                    createdAtMs = 1L,
                ),
                ChatMessage(
                    id = 2L,
                    role = MessageRole.INCOMING,
                    text = "hi",
                    chatId = "c-2",
                    sessionId = "s1",
                    createdAtMs = 2L,
                ),
            ),
        )

        val (sid, payloads) = state.messagesForWire()

        assertEquals("s1", sid)
        assertEquals(2, payloads.size)
        assertEquals(1L, payloads[0].id)
        assertEquals(MessageRole.OUTGOING, payloads[0].role)
        assertEquals("hello", payloads[0].text)
        assertEquals("c-1", payloads[0].chatId)
        assertEquals(2L, payloads[1].id)
        assertEquals(MessageRole.INCOMING, payloads[1].role)
    }

    @Test
    fun `messagesForWire allows null currentSessionId`() {
        val state = PhoneUiState(currentSessionId = null, messages = emptyList())

        val (sid, payloads) = state.messagesForWire()

        assertNull(sid)
        assertTrue(payloads.isEmpty())
    }

    @Test
    fun `default PhoneUiState yields empty session list and null current session`() {
        val state = PhoneUiState()

        assertTrue(state.sessionListForWire().isEmpty())
        val (sid, payloads) = state.messagesForWire()
        assertNull(sid)
        assertTrue(payloads.isEmpty())
        // current_session wire は filter しない契約: list に居ない session が current
        // を指すケースが起きうる (§3.4.1 の補注)。本 test ではその経路は触らないが、
        // mapping 関数自体は currentSessionId を素通しすることだけ assert する。
        assertNotNull(state)
    }
}
