// EventCodec JSON 往復テスト。OpenAI Realtime API の wire 互換性が要 (POC 知見の蓄積)。

package com.example.claudemobilehud.phone.data.transcription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `sessionUpdate emits type and rate fields per OpenAI schema`() {
        val s = EventCodec.sessionUpdate(TranscriptionConfig(apiKey = "test"))
        val obj = json.parseToJsonElement(s).jsonObject
        assertEquals("session.update", obj["type"]?.jsonPrimitive?.content)
        val session = obj["session"]?.jsonObject
        assertNotNull(session)
        assertEquals("transcription", session?.get("type")?.jsonPrimitive?.content)
        val rate = session
            ?.get("audio")?.jsonObject
            ?.get("input")?.jsonObject
            ?.get("format")?.jsonObject
            ?.get("rate")?.jsonPrimitive?.content
        assertEquals("24000", rate)
        val model = session
            ?.get("audio")?.jsonObject
            ?.get("input")?.jsonObject
            ?.get("transcription")?.jsonObject
            ?.get("model")?.jsonPrimitive?.content
        assertEquals("gpt-realtime-whisper", model)
    }

    @Test
    fun `appendAudio wraps base64 string`() {
        val s = EventCodec.appendAudio("AAAA")
        val obj = json.parseToJsonElement(s).jsonObject
        assertEquals("input_audio_buffer.append", obj["type"]?.jsonPrimitive?.content)
        assertEquals("AAAA", obj["audio"]?.jsonPrimitive?.content)
    }

    @Test
    fun `decode session_updated yields SessionReady`() {
        val ev = EventCodec.decode("""{"type":"session.updated"}""")
        assertTrue(ev is TranscriptionEvent.SessionReady, "got: $ev")
    }

    @Test
    fun `decode transcription_session_updated also yields SessionReady`() {
        val ev = EventCodec.decode("""{"type":"transcription_session.updated"}""")
        assertTrue(ev is TranscriptionEvent.SessionReady, "got: $ev")
    }

    @Test
    fun `decode session_created is intentionally ignored`() {
        // 設計判断: session.created 時点ではまだ session.update が反映されておらず、
        // ここで音声送信を開始すると初回 chunk が drop される。
        assertNull(EventCodec.decode("""{"type":"session.created"}"""))
    }

    @Test
    fun `decode delta event extracts text`() {
        val ev = EventCodec.decode(
            """{"type":"conversation.item.input_audio_transcription.delta","delta":"hello"}""",
        )
        assertTrue(ev is TranscriptionEvent.Delta)
        assertEquals("hello", (ev as TranscriptionEvent.Delta).text)
    }

    @Test
    fun `decode delta with missing field falls back to empty string`() {
        val ev = EventCodec.decode(
            """{"type":"conversation.item.input_audio_transcription.delta"}""",
        )
        assertEquals("", (ev as TranscriptionEvent.Delta).text)
    }

    @Test
    fun `decode completed event extracts transcript`() {
        val ev = EventCodec.decode(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hi there"}""",
        )
        assertTrue(ev is TranscriptionEvent.Completed)
        assertEquals("hi there", (ev as TranscriptionEvent.Completed).text)
    }

    @Test
    fun `decode error event extracts nested message`() {
        val ev = EventCodec.decode("""{"type":"error","error":{"message":"bad"}}""")
        assertEquals("bad", (ev as TranscriptionEvent.Error).message)
    }

    @Test
    fun `decode unknown type returns null`() {
        assertNull(EventCodec.decode("""{"type":"some.unrelated.event"}"""))
    }

    @Test
    fun `decode invalid json returns null`() {
        assertNull(EventCodec.decode("not json"))
        assertNull(EventCodec.decode(""))
    }

    @Test
    fun `decode missing type returns null`() {
        assertNull(EventCodec.decode("""{"foo":"bar"}"""))
    }
}
