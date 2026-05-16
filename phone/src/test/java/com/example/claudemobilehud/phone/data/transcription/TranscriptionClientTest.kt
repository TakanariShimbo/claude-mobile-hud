// TranscriptionClient の facade 動作: pre-buffer 蓄積 → SessionReady で flush →
// Delta で partial 累積 → Completed で finalized emit + partial reset。
// fake transport / fake mic を差し込んで JVM 上で完結。

package com.example.claudemobilehud.phone.data.transcription

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionClientTest {

    private class FakeTransport(
        private val source: MutableSharedFlow<TranscriptionEvent>,
    ) : TranscriptionTransport {
        val sent = mutableListOf<String>()
        var connected = false
        var closed = false
        override val events: Flow<TranscriptionEvent> get() = source.asSharedFlow()
        override fun connect() { connected = true }
        override fun sendAudio(base64: String) { sent += base64 }
        override fun close() { closed = true }
    }

    private class FakeMic(
        private val source: MutableSharedFlow<ByteArray>,
    ) : MicCaptureSource {
        var started = false
        var stopped = false
        override val frames: Flow<ByteArray> get() = source.asSharedFlow()
        override fun start() { started = true }
        override fun stop() { stopped = true }
    }

    private data class Rig(
        val ev: MutableSharedFlow<TranscriptionEvent>,
        val fr: MutableSharedFlow<ByteArray>,
        val transport: FakeTransport,
        val mic: FakeMic,
    )

    private fun newRig(): Rig {
        val ev = MutableSharedFlow<TranscriptionEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        val fr = MutableSharedFlow<ByteArray>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
        return Rig(ev, fr, FakeTransport(ev), FakeMic(fr))
    }

    @Test
    fun `invalid config sets Error state and does not start`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = ""))
        runCurrent()
        val s = client.state.value
        assertTrue(s is TranscriptionClient.State.Error, "got: $s")
        assertEquals(false, transport.connected)
        assertEquals(false, mic.started)
    }

    @Test
    fun `start connects transport and mic, state becomes Connecting`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        assertEquals(true, transport.connected)
        assertEquals(true, mic.started)
        assertTrue(client.state.value is TranscriptionClient.State.Connecting)
    }

    @Test
    fun `frames before SessionReady are pre-buffered, then flushed on SessionReady`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val fr = rig.fr; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()

        fr.emit(byteArrayOf(1, 2, 3))
        fr.emit(byteArrayOf(4, 5, 6))
        runCurrent()
        // SessionReady 前なので sendAudio はまだ呼ばれていない
        assertEquals(emptyList<String>(), transport.sent)

        ev.emit(TranscriptionEvent.SessionReady)
        runCurrent()
        // 2 frame を flush
        assertEquals(2, transport.sent.size)

        // 以降の frame は即 send
        fr.emit(byteArrayOf(7))
        runCurrent()
        assertEquals(3, transport.sent.size)
    }

    @Test
    fun `Delta accumulates partial then Completed emits finalized and resets partial`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        ev.emit(TranscriptionEvent.SessionReady)
        runCurrent()

        ev.emit(TranscriptionEvent.Delta("Hello "))
        runCurrent()
        ev.emit(TranscriptionEvent.Delta("world"))
        runCurrent()
        val listening = client.state.value
        assertTrue(listening is TranscriptionClient.State.Listening, "got: $listening")
        assertEquals("Hello world", (listening as TranscriptionClient.State.Listening).partial)

        val finalizedCollector = mutableListOf<String>()
        val finalJob = launch {
            client.finalized.collect { finalizedCollector += it }
        }
        ev.emit(TranscriptionEvent.Completed("Hello world."))
        runCurrent()
        finalJob.cancel()
        assertEquals(listOf("Hello world."), finalizedCollector)
        val after = client.state.value as TranscriptionClient.State.Listening
        assertEquals("", after.partial, "partial should reset after Completed")
    }

    @Test
    fun `Error event tears down transport and mic, state becomes Error`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()

        ev.emit(TranscriptionEvent.Error("auth failed"))
        runCurrent()
        val s = client.state.value
        assertTrue(s is TranscriptionClient.State.Error, "got: $s")
        assertEquals("auth failed", (s as TranscriptionClient.State.Error).message)
        assertEquals(true, transport.closed)
        assertEquals(true, mic.stopped)
    }

    @Test
    fun `Closed without prior Error transitions to Idle and tears down`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        ev.emit(TranscriptionEvent.Closed)
        runCurrent()
        assertEquals(TranscriptionClient.State.Idle, client.state.value)
        assertEquals(true, transport.closed)
        assertEquals(true, mic.stopped)
    }

    @Test
    fun `Closed after Error preserves Error state`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        ev.emit(TranscriptionEvent.Error("net"))
        runCurrent()
        ev.emit(TranscriptionEvent.Closed)
        runCurrent()
        assertTrue(client.state.value is TranscriptionClient.State.Error)
    }

    @Test
    fun `second start while running is idempotent and does not rebuild transport`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val transport = rig.transport; val mic = rig.mic
        var transportCalls = 0
        var micCalls = 0
        val client = TranscriptionClient(
            transportFactory = { transportCalls++; transport },
            micFactory = { _, _ -> micCalls++; mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        assertEquals(1, transportCalls, "factory called only once for idempotent start")
        assertEquals(1, micCalls)
    }

    @Test
    fun `prebuffer overflow drops newest, keeping leading audio`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val ev = rig.ev; val fr = rig.fr; val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        // 250 frame まで貯めて、さらに 5 frame 追加 → 古い 250 のうち末尾 5 が捨てられる
        // (= pollLast policy)。冒頭 250 frame が残る。
        repeat(255) { i ->
            fr.emit(byteArrayOf(i.toByte()))
            runCurrent()
        }
        ev.emit(TranscriptionEvent.SessionReady)
        runCurrent()
        // 上限 250 frame を保持・flush
        assertEquals(250, transport.sent.size)
    }

    @Test
    fun `stop returns to Idle and tears down`() = runTest(UnconfinedTestDispatcher()) {
        val rig = newRig(); val transport = rig.transport; val mic = rig.mic
        val client = TranscriptionClient(
            transportFactory = { transport },
            micFactory = { _, _ -> mic },
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
        )
        client.start(TranscriptionConfig(apiKey = "k"))
        runCurrent()
        client.stop()
        runCurrent()
        assertEquals(TranscriptionClient.State.Idle, client.state.value)
        assertEquals(true, transport.closed)
        assertEquals(true, mic.stopped)
    }
}
