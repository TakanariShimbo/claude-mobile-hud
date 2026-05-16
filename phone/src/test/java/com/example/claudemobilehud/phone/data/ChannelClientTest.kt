package com.example.claudemobilehud.phone.data

import com.example.claudemobilehud.phone.data.error.PhoneWireError
import com.example.claudemobilehud.phone.data.model.SseEvent
import com.example.claudemobilehud.protocol.PermissionDecision
import com.example.claudemobilehud.protocol.error.SharedWireError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ChannelClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ChannelClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ChannelClient(server.url("/").toString().trimEnd('/'), token = "secret")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `send happy path returns chat_id and includes X-Token header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"chat_id":"c-123","session_id":"s1"}""")
        )
        val result = client.send("hello", sessionId = "s1", image = null, imageBase64 = null)
        assertTrue(result.isSuccess, "expected success: $result")
        val resp = result.getOrThrow()
        assertEquals("c-123", resp.chatId)
        assertEquals("s1", resp.sessionId)
        val recorded = server.takeRequest()
        assertEquals("secret", recorded.getHeader("X-Token"))
        assertEquals("POST", recorded.method)
        assertEquals("/send", recorded.path)
    }

    @Test
    fun `send 401 maps to SharedWireError-Connection-AuthFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client.send("x", sessionId = null, image = null, imageBase64 = null)
        val err = result.exceptionOrNull() as? WireErrorException
            ?: error("expected WireErrorException, got ${result.exceptionOrNull()}")
        assertTrue(err.wireError is SharedWireError.Connection.AuthFailed)
    }

    @Test
    fun `permission 410 maps to SharedWireError-Permission-AlreadyVerdicted`() = runTest {
        server.enqueue(MockResponse().setResponseCode(410))
        val result = client.sendPermissionVerdict("req-1", PermissionDecision.ALLOW)
        val err = result.exceptionOrNull() as? WireErrorException
            ?: error("expected WireErrorException, got ${result.exceptionOrNull()}")
        assertTrue(err.wireError is SharedWireError.Permission.AlreadyVerdicted)
    }

    @Test
    fun `4xx body with image_too_large maps to PhoneWireError-Send-ImageTooLarge`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error_code":"image_too_large","message":"big"}""")
        )
        val result = client.send("x", sessionId = null, image = null, imageBase64 = null)
        val err = result.exceptionOrNull() as? WireErrorException
            ?: error("expected WireErrorException, got ${result.exceptionOrNull()}")
        assertTrue(err.wireError is PhoneWireError.Send.ImageTooLarge)
    }

    @Test
    fun `4xx body with session_not_active maps to PhoneWireError-Send-SessionNotActive`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error_code":"session_not_active","message":"gone"}""")
        )
        val result = client.send("x", sessionId = "s1", image = null, imageBase64 = null)
        val err = result.exceptionOrNull() as? WireErrorException
            ?: error("expected WireErrorException, got ${result.exceptionOrNull()}")
        assertTrue(err.wireError is PhoneWireError.Send.SessionNotActive)
    }

    @Test
    fun `unknown 4xx body falls back to ServerError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(418).setBody("""I'm a teapot"""))
        val result = client.send("x", null, null, null)
        val err = result.exceptionOrNull() as? WireErrorException
            ?: error("expected WireErrorException")
        val srv = err.wireError as SharedWireError.Connection.ServerError
        assertEquals(418, srv.httpCode)
    }

    @Test
    fun `SSE parses 7 event types correctly`() = runTest {
        val frames = buildString {
            append("event: session_snapshot\n")
            append("""data: {"active_session_ids":["a","b"]}""").append("\n\n")
            append("event: permission_snapshot\n")
            append("""data: {"request_ids":["r1"]}""").append("\n\n")
            append("event: permission\n")
            append("""data: {"request_id":"r1","session_id":"a","tool_name":"Bash","description":"d","input_preview":"p"}""").append("\n\n")
            append("event: permission_abort\n")
            append("""data: {"request_id":"r1","reason":"x"}""").append("\n\n")
            append("event: reply\n")
            append("""data: {"chat_id":"c1","session_id":"a","text":"hi"}""").append("\n\n")
            append("event: session_active\n")
            append("""data: {"session_id":"a"}""").append("\n\n")
            append("event: session_inactive\n")
            append("""data: {"session_id":"b"}""").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(frames)
        )
        // Open + 7 frames + Closed = 9 events (server closes after body)
        val collected = client.events().take(9).toList()
        val types = collected.map { it::class.simpleName }
        assertTrue(types.contains("Open"), "got: $types")
        assertTrue(collected.any { it is SseEvent.SessionSnapshot && it.activeSessionIds == listOf("a", "b") })
        assertTrue(collected.any { it is SseEvent.PermissionSnapshot && it.requestIds == listOf("r1") })
        assertTrue(collected.any { it is SseEvent.Permission && it.requestId == "r1" && it.toolName == "Bash" })
        assertTrue(collected.any { it is SseEvent.PermissionAbort && it.reason == "x" })
        assertTrue(collected.any { it is SseEvent.Reply && it.chatId == "c1" && it.text == "hi" })
        assertTrue(collected.any { it is SseEvent.SessionActive && it.sessionId == "a" })
        assertTrue(collected.any { it is SseEvent.SessionInactive && it.sessionId == "b" })
    }

    @Test
    fun `SSE 401 emits AuthFailed and closes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val first = client.events().first { it !is SseEvent.Open }
        assertTrue(first is SseEvent.AuthFailed, "got: $first")
    }

    @Test
    fun `SSE other failure emits Failure with message`() = runTest {
        // すぐ disconnect させて onFailure を発火
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        )
        val first = client.events().first { it !is SseEvent.Open }
        assertTrue(first is SseEvent.Failure || first is SseEvent.AuthFailed, "got: $first")
    }
}
